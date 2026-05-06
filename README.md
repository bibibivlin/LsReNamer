# L's ReNamer

A simple Android app that renames media files based on EXIF metadata.

- **Images (JPG)**: renamed to `IMG_<camera_model>_<capture_date>_<original_name>.jpg`
- **Videos (MP4)**: renamed to `VID_<today_date>_<original_name>.mp4`

Uses the Storage Access Framework — pick source and target directories, then tap Start. Files are copied with new names and the originals are deleted.

Requires Android 14+ (API 35).
