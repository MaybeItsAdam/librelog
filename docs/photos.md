# Photos

Any day can hold photos. They sit on the day, alongside its kept calendar
events, rather than inside an entry (SPEC.md §11, "Photos of the day").

## Choosing a photo

Tap **Add photo** on a day. The chooser shows only photos **taken that day**,
read from the device's own library. A **Choose another photo** button opens
the system picker, which can choose any photo, for days with no library
photos or a photo from elsewhere. On phones, **Take photo** opens the camera.

| Platform | That day's photos | Any photo | Camera |
|---|---|---|---|
| iPhone, iPad, Mac | PhotoKit, `creationDate` within the local day | `PhotosPicker` | iPhone and iPad; saved to the Photos library first so the Mac can find it |
| Android | MediaStore, `DATE_TAKEN` within the local day (`READ_MEDIA_IMAGES`, or the user's partial selection on Android 14+) | Photo Picker (no permission) | `TakePicture` into app storage |
| Windows | The Pictures library, `System.Photo.DateTaken` within the day | `FileOpenPicker` | — |

If library access is denied, the chooser says so and still offers **Choose
another photo**.

## What's stored

The app does the image work its platform is good at: decoding HEIC, scaling,
and reading the date and location. It then calls `Store::add_day_photo` with:

- the original file, which the core moves to `media/<blake3>.<ext>` next to the
  database
- a JPEG preview at most 1024 px on its long side, moved to
  `thumbs/<blake3>-1024.jpg`
- the photo scaled to fit 100 × 100 as RGBA, which the core turns into a
  ~25-byte ThumbHash.

One `attachments` row syncs with the date, size, caption, ThumbHash and
`source_ref`. The bytes never leave the device (SPEC.md §12.2). Keeping the
same file on the same day twice, on any device, lands on the same row.

## On other devices

A device that doesn't have the file draws the ThumbHash (`thumbhash_image`)
as a blurred tile, labelled "On another device".

**iCloud Photos.** When the photo is in the Photos library, the Apple app
records its `PHCloudIdentifier` as `source_ref = "icloud:<stringValue>"`.
Another Apple device signed in to the same iCloud Photos library maps it back
with `PHPhotoLibrary.localIdentifierMappings(for:)` and shows the real photo
from its own library. No bytes pass through LibreLog. A photo that's only
just been taken has no cloud identifier until iCloud has uploaded it, so the
app passes `local_asset = "apple:<localIdentifier>"` instead. When it comes
to the front, it asks `photos_awaiting_source_ref("apple:")` and fills in the
ones that now resolve with `set_photo_source_ref`.

**Google Photos** can't do the same thing. Since March 2025, its API only reads
media the app itself uploaded, and Android's MediaStore ids are per device.
Android and Windows show the blurred tile.

## Not done yet

- Photos aren't in exports yet, so leaving through an export loses them.
- Files of a photo removed on *another* device stay on this one until it's
  removed here too.
- Only the 1024 px preview is made; SPEC.md §3.2's 256 px thumbnail isn't.
