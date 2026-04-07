package org.koitharu.kotatsu.main.ui.protect

import androidx.biometric.AuthenticationResult

private val isSuccessMethod by lazy {
	AuthenticationResult::class.java.methods.firstOrNull {
		it.name == "isSuccess" && it.parameterCount == 0
	}
}

private val authenticationTypeMethod by lazy {
	AuthenticationResult::class.java.methods.firstOrNull {
		it.name == "getAuthenticationType" && it.parameterCount == 0
	}
}

fun AuthenticationResult.didAuthenticate(): Boolean {
	val method = isSuccessMethod
	if (method != null) {
		return (method.invoke(this) as? Boolean) == true
	}
	val authType = authenticationTypeMethod?.invoke(this) as? Int
	return authType?.let { it != 0 } ?: true
}
