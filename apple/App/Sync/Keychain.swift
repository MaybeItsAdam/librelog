import Foundation
import Security

/// The two secrets sync needs, kept in the Keychain and never synced through
/// iCloud Keychain: the journal key would otherwise reach Apple's servers,
/// and the session is per device.
enum Keychain {
    enum Item: String {
        case journalKey = "journal-key"
        case session = "supabase-session"
    }

    private static let service = "uk.co.maybeitssoftware.librelog"

    static func read(_ item: Item) -> Data? {
        var query = base(item)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess else { return nil }
        return out as? Data
    }

    @discardableResult
    static func write(_ item: Item, _ data: Data) -> Bool {
        let update = [kSecValueData as String: data] as CFDictionary
        let status = SecItemUpdate(base(item) as CFDictionary, update)
        if status == errSecSuccess { return true }
        guard status == errSecItemNotFound else { return false }
        var add = base(item)
        add[kSecValueData as String] = data
        // Readable after the first unlock, so background sync works while the
        // phone is locked; never leaves this device.
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    static func delete(_ item: Item) {
        SecItemDelete(base(item) as CFDictionary)
    }

    private static func base(_ item: Item) -> [String: Any] {
        var q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: item.rawValue,
            kSecAttrSynchronizable as String: false,
        ]
        #if os(macOS)
        q[kSecUseDataProtectionKeychain as String] = true
        #endif
        return q
    }
}
