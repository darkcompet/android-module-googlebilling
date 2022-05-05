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
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ConsumeResponseListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchaseHistoryResponseListener
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.SkuDetails
import com.android.billingclient.api.SkuDetailsParams
import tool.compet.core.DkLogcats
import tool.compet.core.DkRunner2
import tool.compet.googlebilling.SecurityChecker.verifyPurchase
import java.util.concurrent.atomic.AtomicBoolean

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

	// Caller can set this to listen event when purchase has finished
	private var purchaseListener: PurchaseListener? = null

	// By default, auto unset `puchaseListener` given from caller
	private var autoUnsetPurchaseListener = true

	// Atomic locking setup billing
	private val setupBillingInProgress = AtomicBoolean()

	// Holds pending tasks which be executed when connected
	private val pendingTaskSet: MutableSet<Runnable> = LinkedHashSet()

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
		private var DEFAULT: DkBillingClient? = null

		@MainThread
		fun install(app: Context): DkBillingClient {
			var ins = DEFAULT
			if (ins == null) {
				ins = DkBillingClient(app).also { DEFAULT = it }
			}
			return ins
		}

		val default: DkBillingClient
			get() {
				return DEFAULT!!
			}
	}

	/**
	 * Caller needs to set `purchaseListener` before start purchase since
	 * billing flow will callback at given purchaseListener.
	 *
	 * By default, this will unset `purchaseListener` when `onPurchaseUpdated()` was called.
	 */
	fun setPurchaseListener(purchaseListener: PurchaseListener?) {
		this.purchaseListener = purchaseListener
	}

	fun setAutoUnsetPurchaseListener(autoUnsetPurchaseListener: Boolean) {
		this.autoUnsetPurchaseListener = autoUnsetPurchaseListener
	}

	/**
	 * Set `purchaseListener` to null to avoid unintentional callback from some where at app-scope.
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
		if (purchaseListener == null) {
			DkLogcats.notice(this, "Maybe you forgot set purchase listener?, pls set it before launch billing flow !")
			return
		}
		try {
			if (responseCode == BillingClient.BillingResponseCode.OK) {
				val purchases = purchasesList ?: ArrayList()
				purchaseListener!!.onPurchasesUpdated(purchases)
			}
			else if (responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
				purchaseListener!!.onPurchaseCancelled()
			}
			else {
				purchaseListener!!.onPurchaseFailed(responseCode)
			}
		}
		finally {
			// Auto unset purchaseListener to avoid unintentional callback from some where
			if (autoUnsetPurchaseListener) {
				purchaseListener = null
				if (BuildConfig.DEBUG) {
					DkLogcats.notice(this, "Unset purchaseListener")
				}
			}
		}
	}

	/**
	 * Purchase item (sku) with in-app type. When done, we callback at given `purchaseListener`.
	 */
	fun purchase(host: Activity?, sku: String) {
		purchase(host, listOf(sku), BillingClient.SkuType.INAPP)
	}

	/**
	 * Subscribe item (sku). When done, we callback at given `purchaseListener`.
	 */
	fun subscribe(host: Activity?, sku: String) {
		purchase(host, listOf(sku), BillingClient.SkuType.SUBS)
	}

	/**
	 * Purchase item (sku). When done, we callback at given `purchaseListener`.
	 */
	fun purchase(host: Activity?, sku: String, skuType: String?) {
		purchase(host, listOf(sku), skuType)
	}

	/**
	 * Purchase item (sku). When done, we callback at given `purchaseListener`.
	 *
	 * @param skuType BillingClient.SkuType.INAPP or BillingClient.SkuType.SUBS
	 */
	fun purchase(host: Activity?, skuList: List<String?>?, skuType: String?) {
		scheduleTask {
			val skuDetailsParams = SkuDetailsParams.newBuilder()
				.setSkusList(skuList!!)
				.setType(skuType!!)
				.build()

			// It is required to query available skus before start billing flow params
			billingClient.querySkuDetailsAsync(skuDetailsParams) { billingResult: BillingResult, skuDetailsList: List<SkuDetails?>? ->
				if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && skuDetailsList != null) {
					for (skuDetails in skuDetailsList) {
						val billingFlowParams = BillingFlowParams.newBuilder()
							.setSkuDetails(skuDetails!!)
							.build()

						// Start purchase flow
						billingClient.launchBillingFlow(host!!, billingFlowParams)
					}
				}
			}
		}
	}

	fun queryInAppPurchases(listener: PurchasesResponseListener) {
		scheduleQueryPurchases(BillingClient.SkuType.INAPP, listener)
	}

	fun querySubscriptionPurchases(listener: PurchasesResponseListener) {
		scheduleQueryPurchases(BillingClient.SkuType.SUBS, listener)
	}

	/**
	 * Query last 11 purchases which user bought via in-app without care state of them.
	 * That is, a purchase which has been `cancelled` or `consumed` will be listed in result.
	 *
	 * Note that, caller should pass listener to hear result callback since
	 * this method does NOT callback at given `purchaseListener` when construct.
	 */
	fun queryInAppPurchaseHistory(listener: PurchaseHistoryResponseListener) {
		scheduleQueryPurchaseHistory(BillingClient.SkuType.INAPP, listener)
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
		scheduleQueryPurchaseHistory(BillingClient.SkuType.SUBS, listener)
	}

	/**
	 * Async query information for sku (id for product, item).
	 *
	 * Note that, caller should pass listener to hear result callback since
	 * this method does NOT callback at given `purchaseListener`.
	 *
	 * @param skuType  BillingClient.SkuType.INAPP or BillingClient.SkuType.SUBS
	 * @param skuList  List of sku which you want to know.
	 * @param callback Even though success or fail, callback will return NonNull list of SkuDetail
	 */
	fun querySkuDetails(
		skuType: String?,
		skuList: List<String?>?,
		callback: DkRunner2<BillingResult?, List<SkuDetails?>?>?
	) {
		scheduleTask {
			val skuDetailsParams = SkuDetailsParams.newBuilder()
				.setSkusList(skuList!!)
				.setType(skuType!!)
				.build()
			billingClient.querySkuDetailsAsync(skuDetailsParams) { billingResult: BillingResult?, skuDetailsList: List<SkuDetails?>? ->
				callback?.run(
					billingResult,
					skuDetailsList
				)
			}
		}
	}

	/**
	 * NOTE: A purchase will be refunded if you don’t acknowledge it within three days.
	 *
	 * Consume a consumable item via purchase token to acknowledge the purchase.
	 * After consumed succeed, the item will be re-purchase again.
	 * It is called as `one-time` purchase, and it is useful when we own point-system
	 * which has own items that are exchangeable with purchase item.
	 *
	 * Note that, caller should pass listener to hear result callback since
	 * this method does NOT callback at given `purchaseListener`.
	 */
	fun consume(purchaseToken: String?, listener: ConsumeResponseListener?) {
		scheduleTask {
			val consumeParams = ConsumeParams.newBuilder()
				.setPurchaseToken(purchaseToken!!)
				.build()
			billingClient.consumeAsync(consumeParams, listener!!)
		}
	}

	/**
	 * NOTE: A purchase will be refunded if you don’t acknowledge it within three days.
	 *
	 * Acknowledge a non-consumable item via purchase token.
	 *
	 * Note that, caller should pass listener to hear result callback since
	 * this method does NOT callback at given `purchaseListener`.
	 */
	fun acknowledge(purchaseToken: String?, listener: AcknowledgePurchaseResponseListener?) {
		scheduleTask {
			val consumeParams = AcknowledgePurchaseParams.newBuilder()
				.setPurchaseToken(purchaseToken!!)
				.build()
			billingClient.acknowledgePurchase(consumeParams, listener!!)
		}
	}

	/**
	 * Check current billing client has connected or not.
	 * If connected, we can begin to execute tasks.
	 */
	val isConnected: Boolean
		get() = billingClient.isReady

	/**
	 * Check a feature is supported or not.
	 *
	 * @param feature BillingClient.FeatureType.*
	 */
	fun isFeatureSupported(feature: String?): Boolean {
		return billingClient.isFeatureSupported(feature!!).responseCode == BillingClient.BillingResponseCode.OK
	}
	// region: Private
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
			synchronized(pendingTaskSet) {
				pendingTaskSet.add(task)

				// When we have acquired lock, we need check connection again since maybe connection
				// has established by other caller.
				// If connected, just execute pending tasks which included given task.
				if (isConnected) {
					if (BuildConfig.DEBUG) {
						DkLogcats.notice(this, "-----> WOW, this is rarely but happened !, just execute pending tasks")
					}
					executePendingTasksLocked()
				}
			}
		}

		// Multiple of calling `billingClient.startConnection()` will cause failure,
		// to avoid that problem, we allow only first caller setup billing.

		// If we do as below, we will get incorrect implementation since
		// suppose the first caller pass the `if` condition, then other callers maybe can pass
		// the `if` condition before the first caller is trying to set the setup-flag to true
		// at next line !
		// if (setupBillingInProgress.get()) {
		//		return;
		// }
		// setupBillingInProgress.set(true);

		// Lock by first caller !
		// By do with it, we make 2 actions at atomic,
		// So after the first caller has changed default `false` value of setup-flag,
		// other callers will got failed to lock since the setup-flag has been changed to `true`
		if (BuildConfig.DEBUG) {
			DkLogcats.info(this, "-----> Attempting acquire setup billing lock...")
		}

		// This needs at least 3 actions (read, if, write)
		// but we can make them to atomic action, well done !
		if (setupBillingInProgress.compareAndSet(false, true)) {
			if (BuildConfig.DEBUG) {
				DkLogcats.info(
					this,
					"-----> Yeah, succeed acquire setup billing lock -> startBillingConnectionWithExponentialRetry"
				)
			}
			startBillingConnectionWithExponentialRetry()
			return
		}
		if (BuildConfig.DEBUG) {
			DkLogcats.info(this, "-----> Worked well, other caller has acquire setup billing lock.")
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
					synchronized(pendingTaskSet) { // wait a time for sync
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
			Math.min(reconnectBillingServiceDelayMillis shl 1, RECONNECT_BILLING_SERVICE_END_DELAY_MILLIS)
	}

	private fun executePendingTasksLocked() {
		for (pendingTask in pendingTaskSet) {
			pendingTask.run()
		}
		pendingTaskSet.clear()
	}

	/**
	 * @param skuType BillingClient.SkuType.*
	 * @param listener Result callback when complete.
	 */
	private fun scheduleQueryPurchases(skuType: String, listener: PurchasesResponseListener) {
		scheduleTask { billingClient.queryPurchasesAsync(skuType, listener) }
	}

	/**
	 * @param skuType BillingClient.SkuType.*
	 * @param listener Result callback when complete.
	 */
	private fun scheduleQueryPurchaseHistory(skuType: String, listener: PurchaseHistoryResponseListener) {
		scheduleTask { billingClient.queryPurchaseHistoryAsync(skuType, listener) }
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
	} // endregion: Private
}
