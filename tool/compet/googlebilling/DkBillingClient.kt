/*
 * Copyright (c) 2017-2020 DarkCompet. All rights reserved.
 */
package tool.compet.googlebilling

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.AcknowledgePurchaseResponseListener
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingFlowParams.ProductDetailsParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ConsumeResponseListener
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchaseHistoryResponseListener
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchaseHistoryParams
import com.android.billingclient.api.QueryPurchasesParams
import tool.compet.core.BuildConfig
import tool.compet.core.DkLogcats
import tool.compet.googlebilling.SecurityChecker.verifyPurchase
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Client billing (in-app, subscribe) lifecycled with the app (not activity or fragment).
 *
 * It's strongly recommended that you have one active BillingClient connection open at one time
 * to avoid multiple PurchasesUpdatedListener callbacks for a single event.
 *
 * # Ref v4: https://github.com/android/play-billing-samples/blob/master/TrivialDriveJava/app/src/main/java/com/sample/android/trivialdrivesample/billing/BillingDataSource.java
 */
class DkBillingClient private constructor(context: Context) : PurchasesUpdatedListener {
	private val billingClient: BillingClient

	// Caller MUST set this before start purchsae to listen purchase events
	private var purchaseListener: PurchaseListener? = null

	// By default, auto unset `puchaseListener` given from caller
	private var autoUnsetPurchaseListener = true

	// Atomic locking setup billing
	private val setupBillingInProgress = AtomicBoolean()

	// Holds pending tasks which be executed when connected
	private val pendingTasks: MutableSet<Runnable> = LinkedHashSet()

	// Sometime we got failed when connect to billing service,
	// by implement re-try connect to perform task, we need below reconnect delay duration
	private val RECONNECT_BILLING_SERVICE_START_DELAY_MILLIS: Long = 1000 // 1s
	private val RECONNECT_BILLING_SERVICE_END_DELAY_MILLIS: Long = 600000 // 10m
	private var reconnectBillingServiceDelayMillis = RECONNECT_BILLING_SERVICE_START_DELAY_MILLIS

	/**
	 * Caller should set this listener for new purchase which was called via `purchase()` or `subscribe()`.
	 */
	interface PurchaseListener {
		/**
		 * Callback with list of `active` purchases.
		 * - Purchase: can be in-app or subscribe type.
		 * - Active purchase: item was bought by user and still valid (not expired...).
		 */
		fun onPurchasesUpdated(purchases: List<Purchase>)

		/**
		 * Called when user cancelled this purchase.
		 */
		fun onPurchaseCancelled()

		/**
		 * Called if purchase failed since got some problem, for eg,. invalid configuration, network error...
		 */
		fun onPurchaseFailed(responseCode: Int)
	}

	/**
	 * NOTE that, because billingClient will be registered to ONE instance connection,
	 * so instead of creating multiple billingClient instance for query, we should make it
	 * as singleton to avoid multiple unintentional callbacks of `onPurchasesUpdated()`
	 * which makes `pubchaseListener` be called multiple times -> very risky for developer.
	 */
	init {
		// Setup billing client
		this.billingClient = BillingClient.newBuilder(context)
			.setListener(this)
			.enablePendingPurchases()
			.build()

		// Connect to billing service at construct time
		startBillingConnection(null)
	}

	companion object {
		// This instance will bind with app-lifecycle
		private var INSTANCE: DkBillingClient? = null

		@MainThread
		fun install(app: Context) = INSTANCE ?: DkBillingClient(app).also { INSTANCE = it }

		val instance
			get() = INSTANCE!!
	}

	/**
	 * Caller needs to set `purchaseListener` before start purchase since
	 * billing flow will callback at given purchaseListener.
	 *
	 * By default, this will unset `purchaseListener` when `onPurchaseUpdated()` was called.
	 */
	fun setPurchaseListener(purchaseListener: PurchaseListener) {
		this.purchaseListener = purchaseListener
	}

	fun setAutoUnsetPurchaseListener(autoUnsetPurchaseListener: Boolean) {
		this.autoUnsetPurchaseListener = autoUnsetPurchaseListener
	}

	/**
	 * Set `purchaseListener` to null to avoid unintentional callback from some where inside app.
	 */
	fun unsetPurchaseListener() {
		purchaseListener = null
	}

	// Called when purchase flow has finished, we callback to caller at this time
	override fun onPurchasesUpdated(billingResult: BillingResult, purchasesList: List<Purchase>?) {
		val responseCode = billingResult.responseCode

		if (BuildConfig.DEBUG) {
			DkLogcats.info(
				this,
				"Origin onPurchasesUpdated, billingResult (code: %d, message: %s), purchasesList: %s",
				responseCode,
				billingResult.debugMessage,
				purchasesList?.toString() ?: "Null"
			)
		}

		val purchaseListener = this.purchaseListener
		if (purchaseListener == null) {
			DkLogcats.warning(this, "Maybe you forgot set purchase listener?, pls set it before launch billing flow !")
			return
		}

		try {
			when (responseCode) {
				BillingClient.BillingResponseCode.OK -> {
					val purchases = purchasesList ?: ArrayList()
					purchaseListener.onPurchasesUpdated(purchases)
				}
				BillingClient.BillingResponseCode.USER_CANCELED -> {
					purchaseListener.onPurchaseCancelled()
				}
				else -> {
					purchaseListener.onPurchaseFailed(responseCode)
				}
			}
		}
		finally {
			// Auto unset purchaseListener to avoid unintentional callback from some where
			if (this.autoUnsetPurchaseListener) {
				this.purchaseListener = null
			}
		}
	}

	/**
	 * Purchase item (sku) with in-app type. When done, we callback at given `purchaseListener`.
	 */
	fun purchase(host: Activity, productId: String) {
		purchaseOrSubscribe(host, listOf(productId), BillingClient.ProductType.INAPP)
	}

	/**
	 * Subscribe item (sku). When done, we callback at given `purchaseListener`.
	 */
	fun subscribe(host: Activity, productId: String) {
		purchaseOrSubscribe(host, listOf(productId), BillingClient.ProductType.SUBS)
	}

	/**
	 * Purchase/Subscribe item (sku). When done, we callback at given `purchaseListener`.
	 */
	fun purchaseOrSubscribe(host: Activity, productId: String, productType: String) {
		purchaseOrSubscribe(host, listOf(productId), productType)
	}

	/**
	 * Purchase/Subscribe item (product). When done, we callback at given `purchaseListener`.
	 *
	 * @param productType One of: BillingClient.ProductType.INAPP, BillingClient.ProductType.SUBS
	 */
	fun purchaseOrSubscribe(host: Activity, productIds: List<String>, productType: String) {
		// Must set listener before purchase
		if (this.purchaseListener == null) {
			throw Exception("Must set purchaseListener before start purchase")
		}

		scheduleTask {
			val productDetailParams = mutableListOf<QueryProductDetailsParams.Product>().let { products ->
				for (productId in productIds) {
					products.add(
						QueryProductDetailsParams.Product.newBuilder()
							.setProductId(productId)
							.setProductType(productType)
							.build()
					)
				}

				QueryProductDetailsParams.newBuilder()
					.setProductList(products)
					.build()
			}

			// It is required to query available products before start billing flow params
			billingClient.queryProductDetailsAsync(productDetailParams) { billingResult, productDetails ->
				if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
					for (productDetail in productDetails) {
						// Note that `subscriptionOfferDetails` can be null if it is an in-app product, not a subscription.
						//val offerToken = productDetail.subscriptionOfferDetails?.get(selectedOfferIndex)?.offerToken ?: return

						val productDetailsParamsList = listOf(
							ProductDetailsParams.newBuilder()
								.setProductDetails(productDetail)
								//.setOfferToken(offerToken)
								.build()
						)

						val billingFlowParams = BillingFlowParams.newBuilder()
							.setProductDetailsParamsList(productDetailsParamsList)
							.build()

						// Start purchase flow
						billingClient.launchBillingFlow(host, billingFlowParams)
					}
				}
			}
		}
	}

	fun queryInAppPurchasesAsync(listener: PurchasesResponseListener) {
		scheduleQueryPurchases(BillingClient.ProductType.INAPP, listener)
	}

	fun querySubscriptionPurchasesAsync(listener: PurchasesResponseListener) {
		scheduleQueryPurchases(BillingClient.ProductType.SUBS, listener)
	}

	/**
	 * Query last 11 purchases which user bought via in-app without care state of them.
	 * That is, a purchase which has been `cancelled` or `consumed` will be listed in result.
	 *
	 * Note that, caller should pass listener to hear result callback since
	 * this method does NOT callback at given `purchaseListener` when construct.
	 */
	fun queryInAppPurchaseHistoryAsync(listener: PurchaseHistoryResponseListener) {
		scheduleQueryPurchaseHistory(BillingClient.ProductType.INAPP, listener)
	}

	/**
	 * Query last 11 purchases which user has subscribed via in-app without care state of them.
	 * That is, a purchase which has been `cancelled` or `consumed` will be listed in result.
	 *
	 * Note that, caller should pass listener to hear result callback since
	 * this method does NOT callback at given `purchaseListener`.
	 */
	fun querySubscriptionPurchaseHistory(listener: PurchaseHistoryResponseListener) {
		if (!isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS)) {
			DkLogcats.warning(this, "Skip query since subscription is not supported by this device.")
			return
		}
		scheduleQueryPurchaseHistory(BillingClient.ProductType.SUBS, listener)
	}

	/**
	 * Async query information for sku (id for product, item).
	 *
	 * Note that, caller should pass listener to hear result callback since
	 * this method does NOT callback at given `purchaseListener`.
	 *
	 * @param productType  BillingClient.SkuType.INAPP or BillingClient.SkuType.SUBS
	 * @param productIds  List of sku which you want to know.
	 * @param listener Even though success or fail, callback will return NonNull list of SkuDetail
	 */
	fun queryProductDetailsAsync(
		productType: String,
		productIds: List<String>,
		listener: (billingResult: BillingResult, productDetails: List<ProductDetails>) -> Unit
	) {
		scheduleTask {
			val productDetailParams = mutableListOf<QueryProductDetailsParams.Product>().let { products ->
				for (productId in productIds) {
					products.add(
						QueryProductDetailsParams.Product.newBuilder()
							.setProductId(productId)
							.setProductType(productType)
							.build()
					)
				}

				QueryProductDetailsParams.newBuilder()
					.setProductList(products)
					.build()
			}
			billingClient.queryProductDetailsAsync(productDetailParams, listener)
		}
	}

	/**
	 * Consume a consumable item via purchase token to acknowledge the purchase.
	 * After consumed succeed, the item will be re-purchase again.
	 *
	 * For consumable (in-app) product, call consumeAsync() is to remove the data in Google side.
	 * So when trigger queryPurchase() should be no record for that.
	 *
	 * Both `consumeAsync()` and `acknowledgePurchase()` also will set the payment to done.
	 * If didn't trigger anyone of them, then it will auto refund after 3 days.
	 * Both of the action also work as acknowledge the payment.
	 */
	fun consumeAsync(purchaseToken: String, listener: ConsumeResponseListener) {
		scheduleTask {
			val consumeParams = ConsumeParams.newBuilder()
				.setPurchaseToken(purchaseToken)
				.build()

			billingClient.consumeAsync(consumeParams, listener)
		}
	}

	/**
	 * For non-consumable (subs) product, call acknowledgePurchase() is to set the purchase record to
	 * acknowledged in Google side. So when you trying to trigger queryPurchase(), it will
	 * show the product is purchased with acknowledged.
	 *
	 * Both `consumeAsync()` and `acknowledgePurchase()` also will set the payment to done.
	 * If didn't trigger anyone of them, then it will auto refund after 3 days.
	 * Both of the action also work as acknowledge the payment.
	 */
	fun acknowledgePurchaseAsync(purchaseToken: String, listener: AcknowledgePurchaseResponseListener) {
		scheduleTask {
			val consumeParams = AcknowledgePurchaseParams.newBuilder()
				.setPurchaseToken(purchaseToken)
				.build()

			billingClient.acknowledgePurchase(consumeParams, listener)
		}
	}

	/**
	 * Check current billing client has connected or not.
	 * If connected, we can begin to execute tasks.
	 */
	val isConnected
		get() = billingClient.isReady

	/**
	 * Check a feature is supported or not.
	 *
	 * @param feature BillingClient.FeatureType.*
	 */
	fun isFeatureSupported(feature: String?): Boolean {
		return billingClient.isFeatureSupported(feature!!).responseCode == BillingClient.BillingResponseCode.OK
	}

	/**
	 * Run given task if connection is granted. Otherwise, retry start connection
	 * and the execute it when we got connection successful callback.
	 */
	private fun scheduleTask(task: Runnable) {
		// Is in connected status, just execute the task
		if (isConnected) {
			task.run()
		}
		else {
			if (BuildConfig.DEBUG) {
				DkLogcats.notice(this, "scheduleTask(): not yet done setup service -> attempt to start billing connection")
			}
			startBillingConnection(task)
		}
	}

	private fun startBillingConnection(task: Runnable?) {
		// If connected and billing service is ready to use,
		// just execute given task
		if (isConnected) {
			task?.run()
			return
		}

		// We are going to setup billing,
		// so append the task to pending tasks first
		if (task != null) {
			// Because when billing setup complete, it executes pending tasks in synchronized
			// block. That action maybe run before below lock-acquire, it will lead to problem
			// when this task will not be executed !
			synchronized(pendingTasks) {
				pendingTasks.add(task)

				// When we have acquired lock, we need check connection again since maybe connection
				// has established by other caller.
				// If connected, just execute pending tasks which included given task.
				if (isConnected) {
					if (BuildConfig.DEBUG) {
						DkLogcats.notice(this, "This is rarely but happened ! Just execute pending tasks")
					}
					executePendingTasksLocked()
				}
			}
		}

		// Multiple of calling `billingClient.startConnection()` will cause failure.
		// To avoid that problem, we allow only first caller setup billing.
		if (setupBillingInProgress.compareAndSet(false, true)) {
			if (BuildConfig.DEBUG) {
				DkLogcats.info(this, "Onetime acquired billing setup-lock -> Start billing connection")
			}
			startBillingConnectionWithExponentialRetry()
		}
	}

	private fun startBillingConnectionWithExponentialRetry() {
		billingClient.startConnection(object : BillingClientStateListener {
			override fun onBillingSetupFinished(billingResult: BillingResult) {
				if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
					if (BuildConfig.DEBUG) {
						DkLogcats.info(this, "-----> Done setup billing !")
					}

					// Unlock setup
					setupBillingInProgress.set(false)

					// Reset delay duration when succeed
					reconnectBillingServiceDelayMillis = RECONNECT_BILLING_SERVICE_START_DELAY_MILLIS

					// Notify all pending callbacks known that connection has been established
					synchronized(pendingTasks) {
						executePendingTasksLocked()
					}
				}
				else {
					DkLogcats.error(
						this,
						"-----> onBillingSetupFinished, but NG billingResult (code: %d, message: %s), pls check reason !, retry connection",
						billingResult.responseCode,
						billingResult.debugMessage
					)
					retryBillingServiceConnectionWithExponentialBackoff()
				}
			}

			override fun onBillingServiceDisconnected() {
				DkLogcats.warning(
					this,
					"-----> onBillingServiceDisconnected, retry delayed %d ms...",
					reconnectBillingServiceDelayMillis
				)
				retryBillingServiceConnectionWithExponentialBackoff()
			}
		})
	}

	private fun retryBillingServiceConnectionWithExponentialBackoff() {
		val handler = Handler(Looper.getMainLooper())
		handler.postDelayed({ startBillingConnectionWithExponentialRetry() }, reconnectBillingServiceDelayMillis)

		// Double delay time to avoid spamming and reduce stress onto server
		reconnectBillingServiceDelayMillis =
			min(reconnectBillingServiceDelayMillis shl 1, RECONNECT_BILLING_SERVICE_END_DELAY_MILLIS)
	}

	private fun executePendingTasksLocked() {
		for (pendingTask in pendingTasks) {
			pendingTask.run()
		}
		pendingTasks.clear()
	}

	/**
	 * @param productType BillingClient.ProductType.*
	 * @param listener Result callback when complete.
	 */
	private fun scheduleQueryPurchases(productType: String, listener: PurchasesResponseListener) {
		scheduleTask {
			val queryPurchaseParams = QueryPurchasesParams.newBuilder()
				.setProductType(productType)
				.build()

			billingClient.queryPurchasesAsync(queryPurchaseParams, listener)
		}
	}

	/**
	 * @param productType BillingClient.ProductType.*
	 * @param listener Result callback when complete.
	 */
	private fun scheduleQueryPurchaseHistory(productType: String, listener: PurchaseHistoryResponseListener) {
		scheduleTask {
			val queryPurchaseHistoryParams = QueryPurchaseHistoryParams.newBuilder()
				.setProductType(productType)
				.build()

			billingClient.queryPurchaseHistoryAsync(queryPurchaseHistoryParams, listener)
		}
	}

	/**
	 * This is should be verified at server.
	 * For now, since there is problem with app-public-key, we don't know
	 * where to get that key since it was moved/changed by google billing service...
	 */
	private fun verifyPurchase(appPublicKey: String, purchase: Purchase): Boolean {
		try {
			return verifyPurchase(appPublicKey, purchase.originalJson, purchase.signature)
		}
		catch (e: Exception) {
			DkLogcats.error(this, e)
		}
		return false
	}
}
