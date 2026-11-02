package io.github.kanylbullen.tung.auth

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AuthenticationServices.ASAuthorization
import platform.AuthenticationServices.ASAuthorizationAppleIDCredential
import platform.AuthenticationServices.ASAuthorizationAppleIDProvider
import platform.AuthenticationServices.ASAuthorizationController
import platform.AuthenticationServices.ASAuthorizationControllerDelegateProtocol
import platform.AuthenticationServices.ASAuthorizationControllerPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASAuthorizationErrorCanceled
import platform.AuthenticationServices.ASAuthorizationErrorDomain
import platform.AuthenticationServices.ASAuthorizationScopeEmail
import platform.AuthenticationServices.ASPresentationAnchor
import platform.Foundation.NSError
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * Sign in with Apple on an iPhone: Apple's own sheet over the app's window
 * (AuthenticationServices), asking for the email only (Tung keeps no names),
 * and the identity token it gives, whose audience is this app's bundle id.
 * The server trades it at `/auth/apple` when its `TUNG_APPLE_CLIENT_ID`
 * lists that bundle id. Needs the `com.apple.developer.applesignin`
 * entitlement in the app (iosApp/).
 */
@OptIn(ExperimentalForeignApi::class)
suspend fun appleIdToken(): AppleSignInResult = suspendCancellableCoroutine { continuation ->
    val request = ASAuthorizationAppleIDProvider().createRequest().apply {
        requestedScopes = listOf(ASAuthorizationScopeEmail)
    }
    val controller = ASAuthorizationController(authorizationRequests = listOf(request))
    // The controller holds its delegate weakly: this reference keeps it until Apple answers.
    val delegate = AppleSignInDelegate { result ->
        if (continuation.isActive) continuation.resume(result)
    }
    controller.delegate = delegate
    controller.presentationContextProvider = delegate
    continuation.invokeOnCancellation { delegate.done = {} }
    controller.performRequests()
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class AppleSignInDelegate(var done: (AppleSignInResult) -> Unit) :
    NSObject(),
    ASAuthorizationControllerDelegateProtocol,
    ASAuthorizationControllerPresentationContextProvidingProtocol {

    @ObjCSignatureOverride
    override fun authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization: ASAuthorization) {
        val credential = didCompleteWithAuthorization.credential as? ASAuthorizationAppleIDCredential
        val token = credential?.identityToken?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() }
        done(if (token != null) AppleSignInResult.Token(token) else AppleSignInResult.Failed("no identity token"))
    }

    @ObjCSignatureOverride
    override fun authorizationController(controller: ASAuthorizationController, didCompleteWithError: NSError) {
        val cancelled = didCompleteWithError.domain == ASAuthorizationErrorDomain && didCompleteWithError.code == ASAuthorizationErrorCanceled
        done(if (cancelled) AppleSignInResult.Cancelled else AppleSignInResult.Failed(didCompleteWithError.localizedDescription))
    }

    override fun presentationAnchorForAuthorizationController(controller: ASAuthorizationController): ASPresentationAnchor =
        UIApplication.sharedApplication.keyWindow ?: UIWindow()
}
