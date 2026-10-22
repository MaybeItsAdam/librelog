import SwiftUI

/// The entry body editor: a TextKit 2 text view driven by `EditorController`.
struct RichTextEditor: View {
    let controller: EditorController
    var placeholder: String = String(localized: "Write about your day…")
    @State private var isEmpty = true

    var body: some View {
        PlatformTextView(controller: controller, isEmpty: $isEmpty)
            .overlay(alignment: .topLeading) {
                if isEmpty {
                    Text(placeholder)
                        .font(Typeface.body)
                        .foregroundStyle(Color.dim)
                        .padding(.top, 8)
                        .padding(.leading, 5)
                        .allowsHitTesting(false)
                }
            }
    }
}

#if canImport(UIKit)
import UIKit

private final class EditorTextView: UITextView, EditorHost {
    var storage: NSTextStorage { textStorage }
    var selection: NSRange {
        get { selectedRange }
        set { selectedRange = newValue }
    }
    var typing: [NSAttributedString.Key: Any] {
        get { typingAttributes }
        set { typingAttributes = newValue }
    }
}

private struct PlatformTextView: UIViewRepresentable {
    let controller: EditorController
    @Binding var isEmpty: Bool

    func makeUIView(context: Context) -> UITextView {
        let tv = EditorTextView(usingTextLayoutManager: true)
        tv.backgroundColor = .clear
        tv.textContainerInset = UIEdgeInsets(top: 8, left: 0, bottom: 120, right: 0)
        tv.adjustsFontForContentSizeCategory = true
        tv.keyboardDismissMode = .interactive
        tv.alwaysBounceVertical = true
        tv.isScrollEnabled = false // the screen's ScrollView scrolls
        tv.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        tv.delegate = context.coordinator
        tv.linkTextAttributes = [:] // links are styled by BlockText
        // Loading can happen inside a view update (when the text view
        // attaches), where SwiftUI drops state changes, so hide the
        // placeholder on the next turn.
        controller.onLoad = { [weak tv, coordinator = context.coordinator] in
            DispatchQueue.main.async {
                guard let tv else { return }
                coordinator.updateEmpty(tv)
                tv.invalidateIntrinsicContentSize()
            }
        }
        controller.host = tv
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tapped(_:)))
        tap.delegate = context.coordinator
        tv.addGestureRecognizer(tap)
        context.coordinator.updateEmpty(tv)
        return tv
    }

    func updateUIView(_ tv: UITextView, context: Context) {}

    func sizeThatFits(_ proposal: ProposedViewSize, uiView tv: UITextView, context: Context) -> CGSize? {
        let width = proposal.width ?? 320
        let size = tv.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude))
        return CGSize(width: width, height: max(size.height, 240))
    }

    func makeCoordinator() -> Coordinator { Coordinator(controller: controller, isEmpty: $isEmpty) }

    @MainActor
    final class Coordinator: NSObject, UITextViewDelegate, UIGestureRecognizerDelegate {
        let controller: EditorController
        var isEmpty: Binding<Bool>

        init(controller: EditorController, isEmpty: Binding<Bool>) {
            self.controller = controller
            self.isEmpty = isEmpty
        }

        func textView(_ tv: UITextView, shouldChangeTextIn range: NSRange, replacementText text: String) -> Bool {
            let allowed = controller.shouldChange(range, replacement: text)
            if !allowed {
                updateEmpty(tv)
                tv.invalidateIntrinsicContentSize()
            }
            return allowed
        }

        func textViewDidChange(_ tv: UITextView) {
            controller.textDidChange()
            updateEmpty(tv)
            tv.invalidateIntrinsicContentSize()
        }

        func textViewDidChangeSelection(_ tv: UITextView) {
            controller.selectionDidChange()
        }

        func updateEmpty(_ tv: UITextView) {
            let empty = tv.textStorage.length == 0
            if isEmpty.wrappedValue != empty { isEmpty.wrappedValue = empty }
        }

        @objc func tapped(_ g: UITapGestureRecognizer) {
            guard let tv = g.view as? UITextView else { return }
            let point = g.location(in: tv)
            guard let pos = tv.closestPosition(to: point) else { return }
            let index = tv.offset(from: tv.beginningOfDocument, to: pos)
            // closestPosition lands after the glyph on its trailing half.
            for i in [index, index - 1] where controller.isCheckMarker(atCharacter: i) {
                // The box cross-fades to its new state, with a tick of feedback.
                UIView.transition(with: tv, duration: UIAccessibility.isReduceMotionEnabled ? 0 : 0.18,
                                  options: [.transitionCrossDissolve, .allowUserInteraction]) {
                    _ = self.controller.toggleCheck(atCharacter: i)
                }
                UISelectionFeedbackGenerator().selectionChanged()
                updateEmpty(tv)
                return
            }
        }

        func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
            true
        }
    }
}

#else
import AppKit

private final class EditorTextView: NSTextView, EditorHost {
    weak var controller: EditorController?

    var storage: NSTextStorage { textStorage! }
    var selection: NSRange {
        get { selectedRange() }
        set { setSelectedRange(newValue) }
    }
    var typing: [NSAttributedString.Key: Any] {
        get { typingAttributes }
        set { typingAttributes = newValue }
    }

    override func mouseDown(with event: NSEvent) {
        let point = convert(event.locationInWindow, from: nil)
        let index = characterIndexForInsertion(at: point)
        for i in [index, index - 1] where controller?.isCheckMarker(atCharacter: i) == true {
            _ = controller?.toggleCheck(atCharacter: i)
            NSHapticFeedbackManager.defaultPerformer.perform(.alignment, performanceTime: .now)
            return
        }
        super.mouseDown(with: event)
    }

    override func keyDown(with event: NSEvent) {
        // ⌘B / ⌘I / ⌘U go to our marks rather than AppKit's font panel.
        if event.modifierFlags.contains(.command), let c = event.charactersIgnoringModifiers?.lowercased() {
            switch c {
            case "b": controller?.toggle(BlockText.bold); return
            case "i": controller?.toggle(BlockText.italic); return
            case "u": controller?.toggle(BlockText.underline); return
            default: break
            }
        }
        if event.keyCode == 48 { // Tab / ⇧Tab indent list items
            controller?.indent(event.modifierFlags.contains(.shift) ? -1 : 1)
            return
        }
        super.keyDown(with: event)
    }
}

private struct PlatformTextView: NSViewRepresentable {
    let controller: EditorController
    @Binding var isEmpty: Bool

    func makeNSView(context: Context) -> NSScrollView {
        let scroll = NSTextView.scrollableTextView()
        scroll.drawsBackground = false
        scroll.hasVerticalScroller = true
        let old = scroll.documentView as! NSTextView
        let tv = EditorTextView(frame: old.frame, textContainer: old.textContainer)
        tv.controller = controller
        tv.drawsBackground = false
        tv.isRichText = true
        tv.allowsUndo = true
        tv.isAutomaticQuoteSubstitutionEnabled = true
        tv.isAutomaticDashSubstitutionEnabled = true
        tv.isAutomaticSpellingCorrectionEnabled = false
        tv.textContainerInset = NSSize(width: 0, height: 8)
        tv.autoresizingMask = [.width]
        tv.delegate = context.coordinator
        tv.linkTextAttributes = [:]
        scroll.documentView = tv
        controller.onLoad = { [weak tv, coordinator = context.coordinator] in
            DispatchQueue.main.async { if let tv { coordinator.updateEmpty(tv) } }
        }
        controller.host = tv
        context.coordinator.updateEmpty(tv)
        return scroll
    }

    func updateNSView(_ scroll: NSScrollView, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(controller: controller, isEmpty: $isEmpty) }

    @MainActor
    final class Coordinator: NSObject, NSTextViewDelegate {
        let controller: EditorController
        var isEmpty: Binding<Bool>

        init(controller: EditorController, isEmpty: Binding<Bool>) {
            self.controller = controller
            self.isEmpty = isEmpty
        }

        func textView(_ tv: NSTextView, shouldChangeTextIn range: NSRange, replacementString text: String?) -> Bool {
            let allowed = controller.shouldChange(range, replacement: text ?? "")
            if !allowed { updateEmpty(tv) }
            return allowed
        }

        func textDidChange(_ n: Notification) {
            controller.textDidChange()
            if let tv = n.object as? NSTextView { updateEmpty(tv) }
        }

        func textViewDidChangeSelection(_ n: Notification) {
            controller.selectionDidChange()
        }

        func updateEmpty(_ tv: NSTextView) {
            let empty = (tv.textStorage?.length ?? 0) == 0
            if isEmpty.wrappedValue != empty { isEmpty.wrappedValue = empty }
        }
    }
}
#endif
