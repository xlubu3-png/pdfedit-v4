export interface AppInfo {
  version: string
  /** Fonts read from this computer's font folders. */
  installedFonts: number
}

/** Whether a newer version of the app is published; `enabled` is false outside the installed app. */
export interface UpdateInfo {
  enabled: boolean
  current: string
  latest: string | null
  newer: boolean
  releaseUrl: string | null
  notes: string | null
  error: string | null
}

export interface UploadResponse {
  documentId: string
  fileName: string
  pageCount: number
}

export interface PageItem {
  key: string
  documentId: string
  sourceFileName: string
  pageIndex: number
  rotation: 0 | 90 | 180 | 270
  /** Bumped whenever the page's text edits change, so its thumbnail is re-fetched. */
  textRevision: number
}

/** A line of text on a page; coordinates are PDF user space (origin bottom-left, y-up). */
export interface TextRun {
  index: number
  text: string
  currentText: string
  edited: boolean
  x: number
  y: number
  width: number
  height: number
  fontSize: number
  boxBottom: number
  boxTop: number
  fontFamily: string
  bold: boolean
  /** Font the original PDF names for this line ("Type3 글꼴" for glyph-drawn fonts). */
  sourceFont: string
  /** "#rrggbb" the line was printed in. */
  color: string
  /** An edit that only changes the text can keep the page's own glyphs (the original look). */
  glyphReuse: boolean
  /** Characters this line's own fonts draw on the page; a typed character outside them gets another font. */
  ownChars: string
  /** The page's font for this line is a named font that is not installed on this PC. */
  sourceFontMissing: boolean
  /** An edited line: its original letters were cut out of the page (true) rather than painted over (false). */
  originalRemoved: boolean
  /**
   * How closely the installed font chosen for this line looks like the page's own (0 to 1), when that had to be
   * judged from the glyphs because the font has no name to go by; null otherwise.
   */
  matchSimilarity: number | null
  /** What the user chose for this line so far; null means "as the original". */
  editFontFamily: string | null
  editFontSize: number | null
  editBold: boolean | null
  editColor: string | null
  /** Parts of the edited text with a look of their own (bold, colour); empty when none. */
  editSpans: Span[]
  /** How far the line was moved, in points (y up). */
  dx: number
  dy: number
}

/**
 * Characters `start` (included) to `end` (excluded) of an edited line, bold or not and in a colour of their
 * own; a null `bold` or `color` leaves that to the line.
 */
export interface Span {
  start: number
  end: number
  bold: boolean | null
  color: string | null
  /** Size in points. */
  size: number | null
  underline: boolean | null
}

/** A text box the user put on the page; coordinates are PDF user space, `y` is the first baseline. */
export interface AddedText {
  id: string
  text: string
  x: number
  y: number
  fontFamily: string | null
  fontSize: number
  bold: boolean
  color: string | null
}

/** Everything the user did to one text line: its text, look and place. Nulls mean "as the original". */
export interface RunState {
  text: string
  fontFamily: string | null
  fontSize: number | null
  bold: boolean | null
  color: string | null
  /** Parts of the text that are bold or coloured on their own, on top of the line's look. */
  spans: Span[]
  dx: number
  dy: number
}

/** One text line that contains the searched text. */
export interface FindMatch {
  documentId: string
  pageIndex: number
  runIndex: number
  text: string
}

export interface ReplaceResult {
  replacements: number
  runsChanged: number
  /** Pages that changed (their previews and thumbnails are out of date). */
  pages: { documentId: string; pageIndex: number; rotation: number }[]
  /** Why some pages were left alone, or null. */
  skipped: string | null
}

export interface FontChoice {
  name: string
  label: string
  korean: boolean
}

export interface PageText {
  pageWidth: number
  pageHeight: number
  originX: number
  originY: number
  rotated: boolean
  runs: TextRun[]
  added: AddedText[]
}
