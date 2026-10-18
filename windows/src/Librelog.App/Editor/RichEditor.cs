using Librelog.App.Services;
using Librelog.Core;
using Librelog.Editor;
using Microsoft.UI;
using Microsoft.UI.Input;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Windows.System;
using Windows.UI;
using Windows.UI.Core;

namespace Librelog.App.Editor;

/// The entry body editor (SPEC.md §6.2): a RichEditBox that only displays
/// EditorController's document.
///
/// RichEditBox can't carry meaning (block kinds, list markers, marks), so it
/// isn't the source of truth. Keys with editor behaviour (Return, Backspace,
/// Tab, Ctrl+B/I/U) go to the controller; any other text change is diffed
/// into the controller; then the box is redrawn from the document's styles.
/// RichEditBox's own undo is off for the same reason: undoing a redraw would
/// desynchronise it from the document.
public sealed partial class RichEditor : UserControl
{
    private readonly RichEditBox box;
    private bool rendering;

    /// The document text as last drawn ('\n' paragraphs, no trailing mark).
    private string shown = "";

    public RichEditor()
    {
        box = new RichEditBox
        {
            AcceptsReturn = true,
            IsSpellCheckEnabled = true,
            TextWrapping = TextWrapping.Wrap,
            BorderThickness = new Thickness(0),
            Padding = new Thickness(0, 8, 0, 48),
            MinHeight = 320,
            FontFamily = AppearanceService.ReadingFont,
            FontSize = Appearance.BodySize * AppearanceService.Scale,
            PlaceholderText = "Write about your day…",
            DisabledFormattingAccelerators = DisabledFormattingAccelerators.All,
            ClipboardCopyFormat = RichEditClipboardFormat.PlainText,
        };
        box.Document.UndoLimit = 0;
        box.TextChanged += OnTextChanged;
        box.SelectionChanged += OnSelectionChanged;
        box.PreviewKeyDown += OnPreviewKeyDown;
        box.AddHandler(TappedEvent, new TappedEventHandler(OnTapped), handledEventsToo: true);
        box.ActualThemeChanged += (_, _) => RenderAll();
        Content = box;

        Controller.SelectionChanged += () => StateChanged?.Invoke();
    }

    public EditorController Controller { get; } = new();

    /// The caret moved or a format toggled: refresh the format bar.
    public event Action? StateChanged;

    public void Load(IReadOnlyList<Block> blocks)
    {
        Controller.Load(blocks);
        RenderAll();
    }

    public List<Block> Blocks() => Controller.Blocks();

    public new void Focus(FocusState state) => box.Focus(state);

    // --- Format bar -------------------------------------------------------------

    public void Toggle(Inline flag) => Do(() => Controller.Toggle(flag));
    public void SetKind(BlockKind kind) => Do(() => Controller.SetKind(kind));
    public void Indent(int delta) => Do(() => Controller.Indent(delta));

    private void Do(Action edit)
    {
        edit();
        RenderAll();
        box.Focus(FocusState.Programmatic);
    }

    // --- Input ------------------------------------------------------------------

    private void OnPreviewKeyDown(object sender, KeyRoutedEventArgs e)
    {
        var ctrl = Down(VirtualKey.Control);
        var shift = Down(VirtualKey.Shift);
        switch (e.Key)
        {
            case VirtualKey.Enter:
                // Shift+Enter would insert a soft break the block model can't hold.
                Controller.Return();
                break;
            case VirtualKey.Back when !ctrl:
                if (!Controller.Backspace()) return;
                break;
            case VirtualKey.Tab when !ctrl:
                if (!Controller.Tab(outdent: shift)) return;
                break;
            case VirtualKey.B when ctrl:
                Controller.Toggle(Inline.Bold);
                break;
            case VirtualKey.I when ctrl:
                Controller.Toggle(Inline.Italic);
                break;
            case VirtualKey.U when ctrl:
                Controller.Toggle(Inline.Underline);
                break;
            default:
                return;
        }
        e.Handled = true;
        RenderAll();
    }

    private void OnTextChanged(object sender, RoutedEventArgs e)
    {
        if (rendering) return;
        var now = BoxText();
        if (now == shown) return; // a formatting-only change
        var caret = box.Document.Selection.StartPosition;
        var (start, removed, inserted) = TextDiff.Compute(shown, now, caret);
        Controller.ApplyEdit(start, removed, inserted);

        if (Controller.Document.Text != now)
        {
            RenderAll(); // the controller changed structure (a shortcut, renumbering)
            return;
        }
        shown = now;
        rendering = true;
        try
        {
            var from = Controller.Document.ParagraphAt(start).Start;
            var to = Controller.Document.ParagraphAt(start + inserted.Length);
            FormatRange(from, to.Start + to.Length - from);
        }
        finally
        {
            rendering = false;
        }
    }

    private void OnSelectionChanged(object sender, RoutedEventArgs e)
    {
        if (rendering || BoxText() != shown) return; // an edit is on its way
        var sel = box.Document.Selection;
        Controller.Select(sel.StartPosition, sel.EndPosition - sel.StartPosition);
        if (Controller.Selection.Start != sel.StartPosition) ApplySelection(); // skipped a list marker
    }

    private void OnTapped(object sender, TappedRoutedEventArgs e)
    {
        var range = box.Document.GetRangeFromPoint(e.GetPosition(box), PointOptions.ClientCoordinates);
        if (Controller.ToggleCheck(range.StartPosition)) RenderAll();
    }

    private static bool Down(VirtualKey key) =>
        InputKeyboardSource.GetKeyStateForCurrentThread(key).HasFlag(CoreVirtualKeyStates.Down);

    // --- Drawing ----------------------------------------------------------------

    private string BoxText()
    {
        box.Document.GetText(TextGetOptions.None, out var text);
        if (text.EndsWith('\r')) text = text[..^1]; // RichEdit's final paragraph mark
        return text.Replace('\r', '\n');
    }

    private void RenderAll()
    {
        rendering = true;
        try
        {
            var text = Controller.Document.Text;
            if (BoxText() != text) box.Document.SetText(TextSetOptions.None, text.Replace('\n', '\r'));
            shown = text;
            FormatRange(0, Controller.Document.Length);
            ApplySelection();
        }
        finally
        {
            rendering = false;
        }
        StateChanged?.Invoke();
    }

    private void ApplySelection()
    {
        var (start, length) = Controller.Selection;
        box.Document.Selection.SetRange(start, start + length);
    }

    private void FormatRange(int start, int length)
    {
        var d = box.Document;
        var doc = Controller.Document;
        var dark = ActualTheme == ElementTheme.Dark;
        d.BatchDisplayUpdates();
        try
        {
            foreach (var (s, l, style) in doc.Runs(start, length))
            {
                // The reading face and size from Settings → Appearance.
                var a = Appearance.Text(style, AppearanceService.Current.ReadingFamily, AppearanceService.Scale);
                var f = d.GetRange(s, s + l).CharacterFormat;
                // Mono falls back to an installed mono face: RichEdit resolves
                // fonts by family name, and app-packaged fonts may not be visible to it.
                f.Name = a.Font == Appearance.Mono ? "Cascadia Mono" : a.Font;
                f.Size = (float)(a.Size * 0.75); // DIPs → points
                f.Bold = a.Bold ? FormatEffect.On : FormatEffect.Off;
                f.Italic = a.Italic ? FormatEffect.On : FormatEffect.Off;
                f.Underline = a.Underline ? UnderlineType.Single : UnderlineType.None;
                f.Strikethrough = a.Strike ? FormatEffect.On : FormatEffect.Off;
                f.ForegroundColor = Palette.Color(a.Foreground, dark);
                f.BackgroundColor = a.Background is { } bg ? Palette.Color(bg, dark) : Colors.Transparent;
            }
            foreach (var p in doc.ParagraphStarts(start, length))
            {
                var (ps, pl) = doc.ParagraphAt(p);
                var first = pl > 0 ? doc.StyleAt(ps) : Controller.Typing;
                var pa = Appearance.Paragraph(BlockText.Decode(first.Kind), first.Indent);
                var pf = d.GetRange(ps, ps + Math.Max(pl, 0)).ParagraphFormat;
                pf.SetIndents((float)(pa.FirstLineIndent * 0.75), (float)(pa.LeftIndent * 0.75), 0);
                pf.SpaceBefore = (float)(pa.SpaceBefore * 0.75);
                pf.SpaceAfter = (float)(pa.SpaceAfter * 0.75);
                pf.SetLineSpacing(LineSpacingRule.Multiple, 1.25f);
            }
        }
        finally
        {
            d.ApplyDisplayUpdates();
        }
    }
}

/// ColorRole → the house palette, per theme (SPEC.md §14).
internal static class Palette
{
    public static Color Color(ColorRole role, bool dark) => role switch
    {
        ColorRole.Ink => dark ? Hex(0xF5F4F7) : Hex(0x444054),
        ColorRole.Muted => dark ? Hex(0xB6B3BF) : Hex(0x6E6B7C),
        // Links take the accent chosen in Settings → Appearance.
        ColorRole.Azure => AppearanceService.Rgb(AppearanceService.Current.AccentColor),
        // Amber at 30% over the raised surface (RichEdit has no alpha).
        ColorRole.HighlightAmber => dark ? Hex(0x665221) : Hex(0xFFECB3),
        ColorRole.CodeWell => dark ? Hex(0x2D2B38) : Hex(0xEDEBEF),
        _ => Hex(0x444054),
    };

    private static Color Hex(int v) => Windows.UI.Color.FromArgb(0xFF, (byte)(v >> 16), (byte)(v >> 8), (byte)v);
}
