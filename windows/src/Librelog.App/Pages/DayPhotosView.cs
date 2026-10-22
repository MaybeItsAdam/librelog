using System.Globalization;
using System.Runtime.InteropServices.WindowsRuntime;
using Librelog.App.Services;
using Librelog.Core;
using Librelog.Photos;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Storage;
using Windows.Storage.FileProperties;
using Windows.Storage.Pickers;

namespace Librelog.App.Pages;

/// The photos kept on a day (docs/photos.md), with Add photo. Shown under
/// the editor and under the calendar's selected day. Tiles show this
/// device's preview, or the ThumbHash blurred and labelled "On another
/// device" when the photo was added elsewhere. A photo's menu also picks
/// the day's preview in the journal, or none.
public sealed partial class DayPhotosView : UserControl
{
    private const string GlyphAdd = "";

    private readonly VariableSizedWrapGrid tiles;
    private readonly TextBlock statusText;
    private readonly Grid header;
    private readonly Button addButton;
    private string? date;
    private string? previewId;  // the photo standing for the day in the journal; null for none
    private bool showAdd = true;
    private int generation;

    public DayPhotosView()
    {
        var label = new TextBlock { Text = "PHOTOS", Style = Res<Style>("MicroLabel"), VerticalAlignment = VerticalAlignment.Center };
        var addContent = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
        addContent.Children.Add(new FontIcon { Glyph = GlyphAdd, FontSize = 12 });
        addContent.Children.Add(new TextBlock { Text = "Add photo" });
        var add = new Button { Style = Res<Style>("QuietButton"), Content = addContent, HorizontalAlignment = HorizontalAlignment.Right };
        ToolTipService.SetToolTip(add, "Add photos taken this day");
        add.Click += (_, _) => _ = Add();
        addButton = add;
        header = new Grid();
        header.Children.Add(label);
        header.Children.Add(add);

        tiles = new VariableSizedWrapGrid { Orientation = Orientation.Horizontal, ItemWidth = 120, ItemHeight = 120 };
        statusText = new TextBlock { Style = Res<Style>("MutedText"), Visibility = Visibility.Collapsed };

        var panel = new StackPanel { Spacing = 10 };
        panel.Children.Add(header);
        panel.Children.Add(statusText);
        panel.Children.Add(tiles);
        Content = panel;

        // Not DataChanged: the editor writes on every keystroke. Photos change
        // here (reloaded after each write) or on another device.
        Loaded += (_, _) => AppServices.Sync.Merged += Reload;
        Unloaded += (_, _) => AppServices.Sync.Merged -= Reload;
    }

    /// The day shown, yyyy-MM-dd.
    public string? Date
    {
        get => date;
        set
        {
            if (date == value) return;
            date = value;
            Reload();
        }
    }

    /// False in the journal, where today's add button is an icon in the
    /// rule under its heading (JournalPage); the section then shows only
    /// once there are photos.
    public bool ShowAdd
    {
        get => showAdd;
        set
        {
            showAdd = value;
            addButton.Visibility = Format.Shown(value);
            header.Visibility = Format.Shown(value || tiles.Children.Count > 0);
        }
    }

    private static T Res<T>(string key) => (T)Application.Current.Resources[key];

    private void Reload() => _ = Load();

    private async Task Load()
    {
        var mine = ++generation;
        if (date is not { } day)
        {
            tiles.Children.Clear();
            return;
        }
        var (photos, preview) = await AppServices.Run(s => (s.DayPhotos(day), s.DayPreviews([day]).FirstOrDefault()?.Id));
        if (mine != generation) return;
        previewId = preview;
        tiles.Children.Clear();
        foreach (var photo in photos) tiles.Children.Add(Tile(photo, photos.Count > 1 && photo.Id == preview));
        header.Visibility = Format.Shown(showAdd || photos.Count > 0);
    }

    private void ShowStatus(string text)
    {
        statusText.Text = text;
        statusText.Visibility = Format.Shown(text.Length > 0);
    }

    // --- Tiles --------------------------------------------------------------------

    private Button Tile(DayPhoto photo, bool isPreview)
    {
        var image = new Image { Stretch = Stretch.UniformToFill };
        var face = new Grid();
        face.Children.Add(image);
        var local = photo.PreviewPath is { } p && File.Exists(p);
        if (!local)
        {
            // Over the photo, so a fixed scrim rather than a themed surface.
            var scrim = new Border
            {
                Background = Res<Brush>("ScrimBrush"),
                VerticalAlignment = VerticalAlignment.Bottom,
                Padding = new Thickness(6, 4, 6, 4),
                Child = new TextBlock { Text = "ON ANOTHER DEVICE", Style = Res<Style>("ScrimLabel") },
            };
            face.Children.Add(scrim);
        }
        if (isPreview)
        {
            face.Children.Add(new Border
            {
                Background = Res<Brush>("ScrimBrush"),
                VerticalAlignment = VerticalAlignment.Top,
                HorizontalAlignment = HorizontalAlignment.Left,
                CornerRadius = new CornerRadius(6),
                Margin = new Thickness(6),
                Padding = new Thickness(6, 4, 6, 4),
                Child = new TextBlock { Text = "PREVIEW", Style = Res<Style>("ScrimLabel") },
            });
        }
        _ = Fill(image, photo, decodeWidth: 224);

        var tile = new Button { Style = Res<Style>("PhotoTileButton"), Content = face, Tag = photo };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(tile, photo.Caption ?? "Photo");
        if (photo.Caption is { Length: > 0 } caption) ToolTipService.SetToolTip(tile, caption);
        tile.Click += (_, _) => _ = View(photo);

        var menu = new MenuFlyout();
        var captionItem = new MenuFlyoutItem { Text = photo.Caption is null ? "Add caption…" : "Edit caption…" };
        captionItem.Click += (_, _) => _ = EditCaption(photo);
        var remove = new MenuFlyoutItem { Text = "Remove from this day", Foreground = Res<Brush>("RaspberryBrush") };
        remove.Click += (_, _) => _ = Remove(photo);
        menu.Items.Add(captionItem);
        // The day's preview in the journal, for every device.
        var (usable, removable) = PhotoDay.PreviewActions(photo.Id, previewId);
        if (usable)
        {
            var use = new MenuFlyoutItem { Text = "Use as preview" };
            use.Click += (_, _) => _ = SetPreview(PreviewChoice.Photo(photo.Id));
            menu.Items.Add(use);
        }
        if (removable)
        {
            var none = new MenuFlyoutItem { Text = "No preview" };
            none.Click += (_, _) => _ = SetPreview(PreviewChoice.None);
            menu.Items.Add(none);
        }
        menu.Items.Add(new MenuFlyoutSeparator());
        menu.Items.Add(remove);
        tile.ContextFlyout = menu;
        return tile;
    }

    private async Task SetPreview(PreviewChoice choice)
    {
        if (date is not { } day) return;
        await AppServices.Write(s => s.SetDayPreview(day, choice));
        Reload();
    }

    /// The best picture this device has: the preview (or, for the viewer, the
    /// original), else the ThumbHash scaled up.
    private static async Task Fill(Image image, DayPhoto photo, int decodeWidth, bool original = false)
    {
        if (await Picture(photo, decodeWidth, original) is { } picture) image.Source = picture;
    }

    /// The picture behind Fill, decoded at `decodeWidth`; null when nothing
    /// can be read, which leaves the plain tile. The journal's day
    /// thumbnails (DayThumb) use it too.
    internal static async Task<ImageSource?> Picture(DayPhoto photo, int decodeWidth, bool original = false)
    {
        var path = original && photo.OriginalPath is { } o && File.Exists(o) ? o
            : photo.PreviewPath is { } p && File.Exists(p) ? p
            : null;
        try
        {
            if (path is not null)
            {
                var file = await StorageFile.GetFileFromPathAsync(path);
                using var stream = await file.OpenReadAsync();
                var bitmap = new BitmapImage { DecodePixelWidth = decodeWidth };
                await bitmap.SetSourceAsync(stream);
                return bitmap;
            }
            if (photo.Thumbhash is { } hash)
            {
                var thumb = await AppServices.Run(s => s.ThumbhashImage(hash));
                var bitmap = new WriteableBitmap((int)thumb.Width, (int)thumb.Height);
                using (var pixels = bitmap.PixelBuffer.AsStream())
                {
                    var bgra = Pixels.RgbaToPremultipliedBgra(thumb.Rgba);
                    pixels.Write(bgra, 0, bgra.Length);
                }
                bitmap.Invalidate();
                return bitmap;
            }
        }
        catch (Exception ex) when (ex is CoreException or IOException or UnauthorizedAccessException or System.Runtime.InteropServices.COMException)
        {
            // A file that can't be read leaves the plain tile.
        }
        return null;
    }

    // --- Viewer, caption, remove ------------------------------------------------------

    private ContentDialog Dialog(string title, object content, string? primary)
    {
        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Style = Res<Style>("DefaultContentDialogStyle"),
            RequestedTheme = ActualTheme,
            Title = title,
            Content = content,
            CloseButtonText = primary is null ? "Close" : "Cancel",
            DefaultButton = primary is null ? ContentDialogButton.Close : ContentDialogButton.Primary,
        };
        if (primary is not null) dialog.PrimaryButtonText = primary;
        return dialog;
    }

    /// A larger look, on a black letterbox (the photo isn't ours to theme).
    private async Task View(DayPhoto photo)
    {
        var image = new Image { Stretch = Stretch.Uniform, MaxHeight = 560, MaxWidth = 840 };
        var frame = new Border { Background = Res<Brush>("LetterboxBrush"), CornerRadius = new CornerRadius(6), Child = image };
        var body = new StackPanel { Spacing = 10 };
        body.Children.Add(frame);
        if (photo.Caption is { Length: > 0 } caption)
            body.Children.Add(new TextBlock { Text = caption, Style = Res<Style>("BodyText") });
        var meta = new List<string>();
        if (photo.TakenAt is { } taken && DateTimeOffset.TryParse(taken, CultureInfo.InvariantCulture, DateTimeStyles.None, out var at))
            meta.Add($"{at.ToLocalTime():dddd d MMMM yyyy}, {at.ToLocalTime():t}");
        if (photo.PreviewPath is null || !File.Exists(photo.PreviewPath)) meta.Add("On another device");
        if (meta.Count > 0) body.Children.Add(new TextBlock { Text = string.Join(" · ", meta), Style = Res<Style>("MonoText") });

        var dialog = Dialog(Format.LongDate(photo.Date), body, null);
        dialog.Resources["ContentDialogMaxWidth"] = 920.0;
        _ = Fill(image, photo, decodeWidth: 1600, original: true);
        await dialog.ShowAsync();
    }

    private async Task EditCaption(DayPhoto photo)
    {
        var box = new TextBox { Text = photo.Caption ?? "", PlaceholderText = "Caption", CornerRadius = new CornerRadius(6), MinWidth = 320 };
        if (await Dialog("Caption", box, "Save").ShowAsync() != ContentDialogResult.Primary) return;
        var caption = box.Text;
        await AppServices.Write(s => s.SetPhotoCaption(photo.Id, caption));
        Reload();
    }

    private async Task Remove(DayPhoto photo)
    {
        var text = new TextBlock
        {
            Text = "It comes off this day on every device. The file is deleted from LibreLog too, unless it's kept on another day.",
            Style = Res<Style>("BodyText"),
        };
        if (await Dialog("Remove this photo?", text, "Remove").ShowAsync() != ContentDialogResult.Primary) return;
        await AppServices.Write(s => s.RemoveDayPhoto(photo.Id));
        Reload();
    }

    // --- Adding -------------------------------------------------------------------

    /// Chooses photos for the day and adds them: the section's own button,
    /// or today's icon in the journal.
    public async Task Add()
    {
        if (date is not { } day) return;
        var files = await Choose(Format.Date(day));
        if (files.Count == 0) return;

        ShowStatus(files.Count == 1 ? "Adding the photo…" : $"Adding {files.Count} photos…");
        var ready = new List<NewPhoto>();
        var failed = new List<string>();
        foreach (var file in files)
        {
            try
            {
                ready.Add(await PhotoImport.Prepare(file));
            }
            catch (Exception)
            {
                // One photo that can't be read doesn't stop the rest: HEIC
                // without Windows' HEIF extension lands here, as does a damaged file.
                failed.Add(file.Name);
            }
        }
        try
        {
            if (ready.Count > 0) await AppServices.Write(s => ready.ForEach(p => s.AddDayPhoto(day, p)));
            Reload();
            ShowStatus(failed.Count == 0 ? "" : $"Couldn't read {string.Join(", ", failed)}.");
        }
        catch (CoreException ex)
        {
            ShowStatus($"Couldn't add: {ex.Message}");
        }
    }

    /// The chooser: that day's photos from the Pictures library, several at
    /// once, plus Choose another photo for anything else.
    private async Task<List<StorageFile>> Choose(DateOnly day)
    {
        var status = new TextBlock { Text = "Looking in your Pictures…", Style = Res<Style>("MutedText") };
        var grid = new GridView { SelectionMode = ListViewSelectionMode.Multiple, MaxHeight = 420, MinWidth = 440 };
        var other = new Button { Style = Res<Style>("PlainButton"), Content = "Choose another photo…" };
        var body = new StackPanel { Spacing = 12 };
        body.Children.Add(status);
        body.Children.Add(grid);
        body.Children.Add(other);

        var dialog = Dialog($"Photos from {Format.LongDate(Format.Ymd(day))}", body, "Add");
        dialog.IsPrimaryButtonEnabled = false;
        var pickOther = false;
        other.Click += (_, _) =>
        {
            pickOther = true;
            dialog.Hide();
        };
        grid.SelectionChanged += (_, _) =>
        {
            var n = grid.SelectedItems.Count;
            dialog.IsPrimaryButtonEnabled = n > 0;
            dialog.PrimaryButtonText = n > 1 ? $"Add {n}" : "Add";
        };

        var showing = dialog.ShowAsync().AsTask();
        await FillChooser(day, grid, status);
        var result = await showing;

        if (pickOther) return await PickAny();
        if (result != ContentDialogResult.Primary) return [];
        return grid.SelectedItems.OfType<FrameworkElement>().Select(f => (StorageFile)f.Tag).ToList();
    }

    private static async Task FillChooser(DateOnly day, GridView grid, TextBlock status)
    {
        List<StorageFile> files;
        try
        {
            files = await PhotoImport.LibraryPhotos(day);
        }
        catch (UnauthorizedAccessException)
        {
            status.Text = "LibreLog can't see your Pictures library. Allow it in Windows Settings → Privacy & security → Pictures, or choose another photo.";
            return;
        }
        catch (Exception ex) when (ex is IOException or System.Runtime.InteropServices.COMException)
        {
            status.Text = "Couldn't read your Pictures library. You can still choose another photo.";
            return;
        }
        status.Text = files.Count switch
        {
            0 => "No photos taken this day in your Pictures library.",
            1 => "1 photo taken this day.",
            _ => $"{files.Count} photos taken this day.",
        };
        foreach (var file in files)
        {
            var image = new Image { Stretch = Stretch.UniformToFill };
            var cell = new Border
            {
                Width = 120,
                Height = 120,
                CornerRadius = new CornerRadius(6),
                Background = Res<Brush>("LetterboxBrush"),
                Child = image,
                Tag = file,
            };
            ToolTipService.SetToolTip(cell, file.Name);
            grid.Items.Add(cell);
            try
            {
                using var thumb = await file.GetThumbnailAsync(ThumbnailMode.PicturesView, 240, ThumbnailOptions.UseCurrentScale);
                var bitmap = new BitmapImage();
                await bitmap.SetSourceAsync(thumb);
                image.Source = bitmap;
            }
            catch (Exception ex) when (ex is IOException or System.Runtime.InteropServices.COMException)
            {
                // No thumbnail: the cell stays blank but can still be chosen.
            }
        }
    }

    /// The system picker, for photos from anywhere.
    private static async Task<List<StorageFile>> PickAny()
    {
        var picker = new FileOpenPicker { SuggestedStartLocation = PickerLocationId.PicturesLibrary, ViewMode = PickerViewMode.Thumbnail };
        foreach (var ext in PhotoDay.Extensions) picker.FileTypeFilter.Add(ext);
        WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(Shell.Window));
        var files = await picker.PickMultipleFilesAsync();
        return files is null ? [] : files.ToList();
    }
}
