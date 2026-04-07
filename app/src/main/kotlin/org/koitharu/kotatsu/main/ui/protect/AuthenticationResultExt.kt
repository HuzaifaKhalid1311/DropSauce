package org.koitharu.kotatsu.main.ui.protect

import androidx.biometric.AuthenticationResult

fun AuthenticationResult.didAuthenticate(): Boolean {
	val isSuccessMethod = javaClass.methods.firstOrNull {
		it.name == "isSuccess" && it.parameterCount == 0
	}
	if (isSuccessMethod != null) {
		return (isSuccessMethod.invoke(this) as? Boolean) == true
	}
	val authTypeMethod = javaClass.methods.firstOrNull {
		it.name == "getAuthenticationType" && it.parameterCount == 0
	}
	val authType = authTypeMethod?.invoke(this) as? Int
	return authType?.let { it != 0 } ?: true
}
