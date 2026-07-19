package com.example.ocr

/**
 * Central place for every tunable constant used by the Screen Text Extractor (OCR) feature.
 * Nothing in this feature should use an inline "magic number" — if you need a new one,
 * add it here with a comment explaining why.
 */
object OcrConstants {

    // --- Tesseract language configuration ---
    // Combined language mode: English + Arabic recognized together in a single pass.
    const val TESSERACT_LANGUAGE = "eng+ara"

    // Tessdata is bundled as assets/tessdata/*.traineddata. Tesseract4Android's
    // TessBaseAPI expects the *parent* directory of "tessdata" (it appends "/tessdata" itself),
    // so at runtime we copy assets/tessdata into <filesDir>/tesseract/tessdata and pass
    // <filesDir>/tesseract as the data path.
    const val TESSDATA_ASSET_DIR = "tessdata"
    const val TESSERACT_DATA_DIR_NAME = "tesseract"

    // Expected approximate sizes (bytes) for the LSTM tessdata_fast files, used only as a
    // build-time sanity check (see app/build.gradle.kts verifyTessdata task). Real files vary
    // release to release, so these are lower bounds with generous headroom, not exact matches.
    const val ENG_TRAINEDDATA_MIN_BYTES = 3_500_000L   // eng.traineddata (fast) is ~4.0-4.6MB
    const val ARA_TRAINEDDATA_MIN_BYTES = 1_200_000L   // ara.traineddata (fast) is ~1.4-1.7MB

    // --- Page Segmentation Mode ---
    // Tesseract4Android's TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK treats the input as one
    // uniform block of text, which is the right assumption for a small cropped screen region
    // (as opposed to the default PSM_AUTO, which assumes a full, multi-column page layout).
    const val PSM_SINGLE_BLOCK_INDEX = 6 // TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK ordinal

    // --- Capture sequencing ---
    // Delay (ms) between hiding Orbit's own overlay UI/bubble and actually capturing the frame,
    // so the compositor has time to finish removing our views before MediaProjection grabs the
    // screen. Without this, captures can include a fading/ghost frame of Orbit's own UI.
    const val UI_DISMISS_RELIABILITY_DELAY_MS = 220L

    // Small delay after VirtualDisplay is registered before we pull the first frame from the
    // ImageReader, since the first callback can occasionally be an empty/black buffer on some
    // OEM skins.
    const val CAPTURE_WARMUP_DELAY_MS = 120L

    // How long we wait for a frame to arrive on the ImageReader before giving up.
    const val CAPTURE_TIMEOUT_MS = 4_000L

    // --- Image preprocessing ---
    // Simple fixed-threshold binarization midpoint (0-255) applied after grayscale + local
    // contrast stretch. Screen-captured UI text is high-contrast by nature, so a global
    // threshold (rather than full adaptive/Otsu) is sufficient and keeps this dependency-free.
    const val BINARIZATION_THRESHOLD = 150

    // --- Vault entry source tags ---
    const val SOURCE_MANUAL = "MANUAL"
    const val SOURCE_OCR = "OCR"

    // --- Selection overlay ---
    const val MIN_SELECTION_SIZE_DP = 40
    const val HANDLE_TOUCH_TARGET_DP = 32
    const val HANDLE_VISUAL_SIZE_DP = 18

    // --- Notification ---
    const val OCR_NOTIFICATION_CHANNEL_ID = "orbit_ocr_channel"
    const val OCR_NOTIFICATION_ID = 4133
}
