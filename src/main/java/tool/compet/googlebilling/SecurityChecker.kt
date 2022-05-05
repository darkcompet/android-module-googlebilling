/*
 * Copyright (c) 2017-2020 DarkCompet. All rights reserved.
 */
package tool.compet.googlebilling

import android.util.Base64
import tool.compet.core.DkLogcats
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

internal object SecurityChecker {
	private const val KEY_FACTORY_ALGORITHM = "RSA"
	private const val SIGNATURE_ALGORITHM = "SHA1withRSA"
	@JvmStatic
	fun verifyPurchase(base64PublicKey: String, signedData: String, signature: String): Boolean {
		if (signedData.isEmpty() || base64PublicKey.isEmpty() || signature.isEmpty()) {
			return false
		}
		val key = generatePublicKey(base64PublicKey)
		return verify(key, signedData, signature)
	}

	private fun generatePublicKey(encodedPublicKey: String): PublicKey {
		return try {
			val decodedKey = Base64.decode(encodedPublicKey, Base64.DEFAULT)
			val keyFactory = KeyFactory.getInstance(KEY_FACTORY_ALGORITHM)
			keyFactory.generatePublic(X509EncodedKeySpec(decodedKey))
		}
		catch (e: Exception) {
			DkLogcats.error(SecurityChecker::class.java, e)
			throw RuntimeException(e)
		}
	}

	private fun verify(publicKey: PublicKey, signedData: String, signature: String): Boolean {
		val signatureBytes: ByteArray
		signatureBytes = try {
			Base64.decode(signature, Base64.DEFAULT)
		}
		catch (e: IllegalArgumentException) {
			return false
		}
		try {
			val signatureAlgorithm = Signature.getInstance(SIGNATURE_ALGORITHM)
			signatureAlgorithm.initVerify(publicKey)
			signatureAlgorithm.update(signedData.toByteArray())
			return signatureAlgorithm.verify(signatureBytes)
		}
		catch (e: Exception) {
			DkLogcats.error(SecurityChecker::class.java, e)
		}
		return false
	}
}