package io.github.kanylbullen.tung.sync

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * A secret in the iPhone's Keychain, a generic password under [service] and
 * [account]: the iPhone's [TokenStore] for the sync session token and the
 * assistant's API key, as EncryptedSharedPreferences is the phone's. Readable
 * after the first unlock, and on this device only, so it is never in a backup
 * or on another iPhone (the phone keeps its token out of backups too).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class KeychainTokenStore(private val service: String, private val account: String) : TokenStore {

    override fun read(): String? = memScoped {
        val query = query(kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne)
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        CFRelease(query)
        if (status != errSecSuccess) return null
        val data = CFBridgingRelease(result.value) as? NSData ?: return null
        NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
    }

    override fun write(token: String?) {
        val match = query()
        SecItemDelete(match)
        CFRelease(match)
        if (token == null) return
        val data = (token as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val value = CFBridgingRetain(data)
        val item = query(kSecValueData to value, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        SecItemAdd(item, null)
        CFRelease(item)
        CFRelease(value)
    }

    /** This item's class, service and account, plus [extra]; the caller releases it. */
    private fun query(vararg extra: Pair<CFTypeRef?, CFTypeRef?>): CFMutableDictionaryRef? {
        val dictionary = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        val serviceRef = CFBridgingRetain(service as NSString)
        val accountRef = CFBridgingRetain(account as NSString)
        CFDictionaryAddValue(dictionary, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(dictionary, kSecAttrService, serviceRef)
        CFDictionaryAddValue(dictionary, kSecAttrAccount, accountRef)
        extra.forEach { (key, value) -> CFDictionaryAddValue(dictionary, key, value) }
        // The dictionary retained them (kCFType callbacks).
        CFRelease(serviceRef)
        CFRelease(accountRef)
        return dictionary
    }
}
