import Foundation

#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

/// What the controller needs from a platform text view.
@MainActor
protocol EditorHost: AnyObject {
    var storage: NSTextStorage { get }
    var selection: NSRange { get set }
    var typing: [NSAttributedString.Key: Any] { get set }
}

/// Editing behaviour shared by the iOS and macOS editors (SPEC.md §6.2):
/// Markdown shortcuts, lists that continue on Return, checkboxes, and the
/// formatting toolbar. Everything goes through `BlockText`'s attributes, so
/// what's on screen is always exactly what `blocks()` will save.
@MainActor
final class EditorController {
    weak var host: EditorHost?
    /// Called after every edit; the screen debounces the save.
    var onChange: (() -> Void)?
    /// Called when the caret moves, so the toolbar can reflect the paragraph.
    var onSelectionChange: (() -> Void)?

    private var applyingEdit = false

    func load(_ blocks: [Block]) {
        guard let host else { return }
        host.storage.setAttributedString(BlockText.attributed(blocks))
        host.selection = NSRange(location: host.storage.length, length: 0)
        syncTypingAttributes()
    }

    func blocks() -> [Block] {
        guard let host else { return [] }
        return BlockText.blocks(host.storage)
    }

    // MARK: Text view callbacks

    /// Return `false` when the controller performed the edit itself.
    func shouldChange(_ range: NSRange, replacement: String) -> Bool {
        guard let host, !applyingEdit else { return true }
        let storage = host.storage
        let ns = storage.string as NSString
        let para = ns.paragraphRange(for: NSRange(location: min(range.location, ns.length), length: 0))
        let kind = kindOf(paragraph: para)
        let markerEnd = markerRange(in: para).map(NSMaxRange) ?? para.location

        // Backspace at the very start of a list item's text: drop the list.
        if replacement.isEmpty, range.length <= 1, range.location < markerEnd,
           host.selection.length == 0, host.selection.location == markerEnd, markerEnd > para.location {
            setKind(.paragraph, paragraphAt: para.location)
            host.selection = NSRange(location: para.location, length: 0)
            changed()
            return false
        }

        guard replacement == "\n" else { return true }

        switch kind {
        case .bullet, .numbered, .check:
            let content = contentText(paragraph: para)
            if content.isEmpty {
                // Return on an empty item ends the list.
                setKind(.paragraph, paragraphAt: para.location)
                host.selection = NSRange(location: para.location, length: 0)
                changed()
                return false
            }
            let next: BlockKind = if case .check = kind { .check(checked: false) } else { kind }
            insertParagraph(at: range, kind: next, indent: indentOf(paragraph: para))
            return false
        case .heading:
            insertParagraph(at: range, kind: .paragraph, indent: 0)
            return false
        default:
            return true
        }
    }

    func textDidChange() {
        guard let host, !applyingEdit else { return }
        let ns = host.storage.string as NSString
        let caret = host.selection.location
        let para = ns.paragraphRange(for: NSRange(location: min(caret, ns.length), length: 0))

        if caret > para.location, kindOf(paragraph: para) == .paragraph || kindOf(paragraph: para) == .bullet,
           let typed = Optional(ns.substring(with: NSRange(location: para.location, length: caret - para.location))),
           typed.hasSuffix(" ") || typed == "---" || typed == "```",
           let match = markdownShortcut(text: typed),
           match.prefixLen == UInt32((typed as NSString).length) {
            applyShortcut(match, paragraph: para)
        }

        BlockText.restyle(host.storage, in: para)
        renumberIfNeeded()
        syncTypingAttributes()
        changed()
    }

    func selectionDidChange() {
        syncTypingAttributes()
        onSelectionChange?()
    }

    // MARK: Toolbar

    var currentKind: BlockKind {
        guard let host else { return .paragraph }
        let ns = host.storage.string as NSString
        let para = ns.paragraphRange(for: NSRange(location: min(host.selection.location, ns.length), length: 0))
        return kindOf(paragraph: para)
    }

    func isActive(_ key: NSAttributedString.Key) -> Bool {
        guard let host else { return false }
        if host.selection.length == 0 { return host.typing[key] != nil }
        return host.storage.attribute(key, at: host.selection.location, effectiveRange: nil) != nil
    }

    /// Bold, italic, etc. With no selection it changes what you type next.
    func toggle(_ key: NSAttributedString.Key) {
        guard let host else { return }
        let sel = host.selection
        if sel.length == 0 {
            var t = host.typing
            if t[key] == nil { t[key] = true } else { t.removeValue(forKey: key) }
            t = restyledTyping(t)
            host.typing = t
            onSelectionChange?()
            return
        }
        let on = host.storage.attribute(key, at: sel.location, effectiveRange: nil) == nil
        if on { host.storage.addAttribute(key, value: true, range: sel) } else { host.storage.removeAttribute(key, range: sel) }
        BlockText.restyle(host.storage, in: sel)
        host.selection = sel
        changed()
    }

    /// Turns the selected paragraphs into `kind`, or back to text if they
    /// already are.
    func setKind(_ kind: BlockKind) {
        guard let host else { return }
        let target: BlockKind = currentKind == kind ? .paragraph : kind
        let ns = host.storage.string as NSString
        var starts: [Int] = []
        ns.enumerateSubstrings(in: ns.paragraphRange(for: host.selection), options: [.byParagraphs, .substringNotRequired]) { _, r, _, _ in
            starts.append(r.location)
        }
        if starts.isEmpty { starts = [ns.paragraphRange(for: host.selection).location] }
        let caretFromEnd = host.storage.length - host.selection.location
        for start in starts.reversed() {
            setKind(target, paragraphAt: start)
        }
        host.selection = NSRange(location: max(0, host.storage.length - caretFromEnd), length: 0)
        renumberIfNeeded()
        syncTypingAttributes()
        changed()
    }

    /// Nests or un-nests list items; other paragraphs don't indent.
    func indent(_ delta: Int) {
        guard let host else { return }
        let para = (host.storage.string as NSString).paragraphRange(for: host.selection)
        switch kindOf(paragraph: para) {
        case .bullet, .numbered, .check: break
        default: return
        }
        let new = max(0, min(4, indentOf(paragraph: para) + delta))
        host.storage.addAttribute(BlockText.indent, value: new, range: para)
        BlockText.restyle(host.storage, in: para)
        changed()
    }

    /// Flips a checkbox when its marker is tapped or clicked.
    func toggleCheck(atCharacter index: Int) -> Bool {
        guard let host, index < host.storage.length,
              host.storage.attribute(BlockText.marker, at: index, effectiveRange: nil) != nil else { return false }
        let ns = host.storage.string as NSString
        let para = ns.paragraphRange(for: NSRange(location: index, length: 0))
        guard case .check(let checked) = kindOf(paragraph: para) else { return false }
        let sel = host.selection
        setKind(.check(checked: !checked), paragraphAt: para.location)
        host.selection = sel
        changed()
        return true
    }

    // MARK: Paragraph surgery

    private func kindOf(paragraph para: NSRange) -> BlockKind {
        guard let host, host.storage.length > 0 else { return .paragraph }
        let at = min(para.location, host.storage.length - 1)
        // An empty last paragraph has no characters; ask the typing attributes.
        if para.length == 0 { return BlockText.decode(host.typing[BlockText.kind] as? String) }
        return BlockText.decode(host.storage.attribute(BlockText.kind, at: at, effectiveRange: nil) as? String)
    }

    private func indentOf(paragraph para: NSRange) -> Int {
        guard let host, para.length > 0 else { return 0 }
        return host.storage.attribute(BlockText.indent, at: para.location, effectiveRange: nil) as? Int ?? 0
    }

    private func markerRange(in para: NSRange) -> NSRange? {
        guard let host, para.length > 0 else { return nil }
        var r = NSRange()
        guard host.storage.attribute(BlockText.marker, at: para.location, effectiveRange: &r) != nil else { return nil }
        return NSIntersectionRange(r, para)
    }

    private func contentText(paragraph para: NSRange) -> String {
        guard let host else { return "" }
        let start = markerRange(in: para).map(NSMaxRange) ?? para.location
        var end = NSMaxRange(para)
        let ns = host.storage.string as NSString
        if end > start, ns.character(at: end - 1) == 0x0A { end -= 1 }
        return end > start ? ns.substring(with: NSRange(location: start, length: end - start)) : ""
    }

    /// Re-kinds the paragraph starting at `start`, swapping its list marker.
    private func setKind(_ kind: BlockKind, paragraphAt start: Int) {
        guard let host else { return }
        applyingEdit = true
        defer { applyingEdit = false }
        let storage = host.storage
        let ns = storage.string as NSString
        var para = ns.paragraphRange(for: NSRange(location: start, length: 0))
        let ind = indentOf(paragraph: para)

        if let m = markerRange(in: para) {
            storage.deleteCharacters(in: m)
            para.length -= m.length
        }
        if let text = BlockText.markerText(kind, number: 1) {
            let attrs: [NSAttributedString.Key: Any] = [BlockText.kind: BlockText.encode(kind), BlockText.indent: ind, BlockText.marker: true]
            storage.insert(NSAttributedString(string: text, attributes: attrs), at: para.location)
            para.length += (text as NSString).length
        }
        if para.length > 0 {
            storage.addAttribute(BlockText.kind, value: BlockText.encode(kind), range: para)
        }
        host.typing = BlockText.typingAttributes(kind, indent: ind)
        BlockText.restyle(storage, in: para)
    }

    private func insertParagraph(at range: NSRange, kind: BlockKind, indent ind: Int) {
        guard let host else { return }
        applyingEdit = true
        let storage = host.storage
        var attrs: [NSAttributedString.Key: Any] = [BlockText.kind: BlockText.encode(kind), BlockText.indent: ind]
        let insertion = NSMutableAttributedString(string: "\n", attributes: attrs)
        if let m = BlockText.markerText(kind, number: 1) {
            attrs[BlockText.marker] = true
            insertion.append(NSAttributedString(string: m, attributes: attrs))
        }
        storage.replaceCharacters(in: range, with: insertion)
        let caret = range.location + insertion.length
        BlockText.restyle(storage, in: NSRange(location: range.location, length: insertion.length))
        host.selection = NSRange(location: caret, length: 0)
        host.typing = BlockText.typingAttributes(kind, indent: ind)
        applyingEdit = false
        renumberIfNeeded()
        changed()
    }

    private func applyShortcut(_ match: Shortcut, paragraph para: NSRange) {
        guard let host else { return }
        applyingEdit = true
        let prefix = NSRange(location: para.location, length: Int(match.prefixLen))
        host.storage.deleteCharacters(in: prefix)
        applyingEdit = false
        setKind(match.kind, paragraphAt: para.location)
        let newPara = (host.storage.string as NSString).paragraphRange(for: NSRange(location: para.location, length: 0))
        let start = markerRange(in: newPara).map(NSMaxRange) ?? newPara.location
        host.selection = NSRange(location: start, length: 0)
    }

    /// Rewrites "1. 2. 3." markers after edits that could shift them.
    private func renumberIfNeeded() {
        guard let host else { return }
        let storage = host.storage
        let ns = storage.string as NSString
        guard ns.length > 0, storage.string.contains("\u{2002}") else { return }
        applyingEdit = true
        defer { applyingEdit = false }
        var number = 0
        var location = 0
        let sel = host.selection
        var shift = 0
        while location < (storage.string as NSString).length {
            let para = (storage.string as NSString).paragraphRange(for: NSRange(location: location, length: 0))
            if kindOf(paragraph: para) == .numbered, let m = markerRange(in: para) {
                number += 1
                let want = "\(number).\u{2002}"
                let have = (storage.string as NSString).substring(with: m)
                if want != have {
                    let attrs = storage.attributes(at: m.location, effectiveRange: nil)
                    storage.replaceCharacters(in: m, with: NSAttributedString(string: want, attributes: attrs))
                    let delta = (want as NSString).length - m.length
                    if m.location < sel.location { shift += delta }
                    BlockText.restyle(storage, in: NSRange(location: para.location, length: para.length + delta))
                }
            } else {
                number = 0
            }
            let next = NSMaxRange((storage.string as NSString).paragraphRange(for: NSRange(location: location, length: 0)))
            if next <= location { break }
            location = next
        }
        if shift != 0 { host.selection = NSRange(location: sel.location + shift, length: sel.length) }
    }

    private func syncTypingAttributes() {
        guard let host else { return }
        let ns = host.storage.string as NSString
        let caret = min(host.selection.location, ns.length)
        let para = ns.paragraphRange(for: NSRange(location: caret, length: 0))
        let k = kindOf(paragraph: para)
        var t = BlockText.typingAttributes(k, indent: indentOf(paragraph: para))
        // Carry inline marks from the character before the caret, as editors do.
        if caret > para.location, caret <= ns.length, caret > 0 {
            let before = host.storage.attributes(at: caret - 1, effectiveRange: nil)
            if before[BlockText.marker] == nil {
                for (key, _) in BlockText.flagKeys where before[key] != nil { t[key] = true }
            }
        }
        host.typing = restyledTyping(t)
    }

    private func restyledTyping(_ t: [NSAttributedString.Key: Any]) -> [NSAttributedString.Key: Any] {
        var t = t
        let k = BlockText.decode(t[BlockText.kind] as? String)
        t[.font] = BlockText.font(for: k, bold: t[BlockText.bold] != nil, italic: t[BlockText.italic] != nil,
                                  code: t[BlockText.code] != nil || k == .code)
        return t
    }

    private func changed() {
        onChange?()
        onSelectionChange?()
    }
}
