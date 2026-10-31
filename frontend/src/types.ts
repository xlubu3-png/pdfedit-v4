export interface AppInfo {
  version: string
  /** Fonts read from this computer's font folders. */
  installedFonts: number
  /** Families installed only as .otf files, which the app cannot read: present on the PC but not usable. */
  unreadableFonts: string[]
}

/** What the user can set in the app. */
export interface AppSettings {
  /** Days an uploaded document nobody opens is kept before it is cleaned up. */
  retentionDays: number
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

/** An area whose content is taken out of the exported PDF and painted black: PDF user space, points. */
export interface Redaction {
  id: string
  x: number
  /** Bottom edge. */
  y: number
  width: number
  height: number
}

export type PersonalInfoKind =
  | 'RRN'
  | 'CARD'
  | 'DRIVER'
  | 'MOBILE'
  | 'PHONE'
  | 'EMAIL'
  | 'BUSINESS'
  | 'ACCOUNT'
  | 'PASSPORT'
  | 'TERM'

/** One piece of personal information found on a page, with the area that would redact it. */
export interface PersonalInfoMatch {
  documentId: string
  pageIndex: number
  kind: PersonalInfoKind
  label: string
  text: string
  x: number
  y: number
  width: number
  height: number
}

/** A rectangle in PDF user space (origin bottom-left, y-up), in points. */
export interface PdfArea {
  x: number
  y: number
  width: number
  height: number
}

/** Text removed from (or added to) one page, one entry per line it touches. */
export interface TextChange {
  page: number
  text: string
  area: PdfArea
}

export interface PageSize {
  width: number
  height: number
  originX: number
  originY: number
}

export interface CompareResult {
  a: string
  b: string
  fileNameA: string
  fileNameB: string
  pagesA: PageSize[]
  pagesB: PageSize[]
  /** In the first document. */
  removed: TextChange[]
  /** In the second document. */
  added: TextChange[]
  removedWords: number
  addedWords: number
}

/** Where a pair of pages looks different; not comparable when they differ in size. */
export interface VisualResult {
  comparable: boolean
  areas: PdfArea[]
}

export interface UploadResponse {
  documentId: string
  fileName: string
  pageCount: number
  /** It was encrypted; the stored copy, and so the export, has no password unless one is set on export. */
  unlocked?: boolean
  /** What the encrypted original allowed (its PDF permission bits); null when it was not encrypted. */
  permissions?: number | null
}

export interface PageItem {
  key: string
  documentId: string
  sourceFileName: string
  pageIndex: number
  rotation: 0 | 90 | 180 | 270
  /** Bumped whenever the page's text edits change, so its thumbnail is re-fetched. */
  textRevision: number
  /** Set for a page of a document that was opened with its password: what the original allowed. */
  lockedPermissions?: number
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
  /** Parts of the text with a look of their own, as character positions in `text` (line breaks counted). */
  spans: Span[]
}

/** A picture (stamp, signature, logo) the user put on the page; coordinates are PDF user space, `y` is the bottom edge. */
export interface AddedImage {
  id: string
  /** The uploaded picture this box shows. */
  imageId: string
  x: number
  y: number
  width: number
  height: number
  /** The white of the picture is made see-through (a photographed signature on paper). */
  keyOutWhite: boolean
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
  images: AddedImage[]
}
