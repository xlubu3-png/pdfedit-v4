export interface AppInfo {
  version: string
  /** Fonts read from this computer's font folders. */
  installedFonts: number
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
  /** What the user chose for this line so far; null means "as the original". */
  editFontFamily: string | null
  editFontSize: number | null
  editBold: boolean | null
  editColor: string | null
  /** How far the line was moved, in points (y up). */
  dx: number
  dy: number
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
