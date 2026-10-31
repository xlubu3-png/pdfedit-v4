import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { PointerEvent } from 'react'
import {
  ApiError,
  fetchFonts,
  fetchPageOcr,
  fetchPageText,
  imageUrl,
  previewUrl,
  ocrTextBox,
  recognizePage,
  reloadFonts,
  resetTextEdits,
  saveAddedImages,
  saveAddedTexts,
  saveTextEdits,
  uploadImage,
} from '../api'
import type { TextEditPayload } from '../api'
import { fontBaseName, missingFontNames, otherFormatFamily, reloadMessage } from '../fontHelp'
import { editorTipSeen, markEditorTipSeen } from '../editorTip'
import { foreignChars, SIMILAR_ENOUGH } from '../fontMix'
import { History } from '../history'
import { keyCode } from '../keys'
import { copyText } from '../clipboard'
import { ocrMessage, recognizeAll } from '../ocr'
import type { SnapTargets } from '../snap'
import { clearField, formatRange, lookAt, remapSpans, toggleFor } from '../spans'
import type { LookPatch } from '../spans'
import type { AddedImage, AddedText, AppInfo, FontChoice, OcrText, PageItem, PageText, RunState, TextRun } from '../types'
import { fitPageZoom, fitZoom, previewDpi, PX_PER_PT_AT_100, zoomStep } from '../zoom'
import { HelpDialog } from './HelpDialog'
import { MissingFontDialog } from './MissingFontDialog'
import { StyleBar, StyleInfo, ZoomControl } from './StyleBar'
import type { ImageView, StyleView } from './StyleBar'
import {
  ALargeSmall,
  ArrowLeft,
  Bold,
  CheckCheck,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  ChevronUp,
  CircleQuestionMark,
  Copy,
  CopyPlus,
  PenLine,
  Eye,
  Files,
  FileDown,
  FileImage,
  FileSearch,
  ImagePlus,
  MoveHorizontal,
  Printer,
  Scan,
  ScanText,
  Redo2,
  RefreshCw,
  RotateCcw,
  Search,
  Trash,
  Type,
  Underline,
  Undo2,
  ZoomIn,
  ZoomOut,
} from 'lucide-react'
import { RibbonButton, RibbonGroup, RibbonPanel, RibbonTabs } from './Ribbon'
import type { RibbonTab } from './Ribbon'
import { AddedBox, ImageBox, RunBox } from './TextBoxes'
import type { Guides } from './TextBoxes'

interface TextEditorProps {
  page: PageItem
  position: number
  total: number
  onNavigate: (delta: -1 | 1) => void
  onClose: () => void
  /** Called after the page's text edits changed on the server. */
  onEdited: (pageKey: string) => void
  /** Downloads the whole page list as a PDF, edits included. */
  onExport: () => Promise<void>
  /** Prints the whole page list as it would be exported. */
  onPrint: () => Promise<void>
  /** Export as a picture of each page: nothing of the original text stays in the file. */
  flatten: boolean
  onFlattenChange: (flatten: boolean) => void
  /** Opens the find-and-replace dialog. */
  onOpenFind: () => void
  /** How many fonts the app read last time, to tell what a reload found. */
  installedFonts: number | undefined
  /** Families installed only as .otf files: the PC has them but the app cannot read them. */
  unreadableFonts: string[]
  /** Called after the installed fonts were read again, with what the server now reports. */
  onFontsReloaded: (info: AppInfo) => void
  /** Called after the text of this page was recognized, with how many lines were found. */
  onRecognized: (pageKey: string, lines: number) => void
  /** Recognizes every page of the list; resolves to what was done, or null when nothing ran. */
  onRecognizeAll: () => Promise<string | null>
  /** How far recognizing every page has got, while it runs. */
  ocrProgress: { done: number; total: number } | null
  onStopRecognize: () => void
  busy: boolean
}

/** The pages of the ribbon; "편집" is open first, it holds what is used most. */
const EDITOR_TABS: RibbonTab[] = [
  { id: 'file', label: '파일' },
  { id: 'edit', label: '편집' },
  { id: 'view', label: '보기' },
  { id: 'insert', label: '입력' },
  { id: 'format', label: '서식' },
  { id: 'tools', label: '도구' },
]

/** Recognized lines read less surely than this are marked (orange) for the user to check. */
const UNSURE = 0.9

/** What the server draws an added box in when no font is chosen. */
const DEFAULT_ADDED_FAMILY = '맑은 고딕'
const DEFAULT_ADDED_SIZE = 12
/** How far a duplicate or a paste lands from the original, in points. */
const PASTE_OFFSET = 12
const HISTORY_LIMIT = 100
/** Changes to the same thing within this many ms (typing in the size box, nudging with arrows) are one undo step. */
const COALESCE_MS = 800

type Selection = { kind: 'run'; index: number } | { kind: 'added'; id: string } | { kind: 'image'; id: string } | null

/** Letters `start` to `end` picked inside the text of a line (`key` "r3") or of an added box (`key` "a<id>"). */
interface PickedRange {
  key: string
  start: number
  end: number
}

/** Everything undo can bring back: what was done to each line, and the boxes that were added. */
interface Snapshot {
  runs: Map<number, RunState>
  added: AddedText[]
  images: AddedImage[]
}

/** How long a Ctrl+V waits for the browser's paste event before it pastes the copied line instead. */
const PASTE_WAIT_MS = 120

/** How wide a picture is first put on the page: its own size at 96 dpi, but at most this part of the page. */
const FIRST_IMAGE_SHARE = 0.4

/** A copied line, text box or picture, ready to be pasted (also onto another page: this outlives the editor). */
type Copied =
  | { kind: 'text'; look: Omit<AddedText, 'id'>; from: { documentId: string; pageIndex: number } }
  | { kind: 'image'; look: Omit<AddedImage, 'id'>; from: { documentId: string; pageIndex: number } }
let clipboard: Copied | null = null

function savedLabel() {
  return `✓ 반영됨 ${new Date().toLocaleTimeString('ko-KR')}`
}

/** Not crypto.randomUUID: that needs a secure context, and the app may be opened over plain http on a NAS. */
function newId() {
  return Math.random().toString(36).slice(2, 10)
}

const round = (value: number) => Math.round(value * 10) / 10
const sameSize = (a: number, b: number) => Math.abs(a - b) < 0.05
const nonEmpty = (b: AddedText) => b.text.trim() !== ''
const normalize = (name: string) => name.toLowerCase().replace(/[\s\-_]/g, '')

function blankState(run: TextRun): RunState {
  return { text: run.text, fontFamily: null, fontSize: null, bold: null, color: null, spans: [], dx: 0, dy: 0 }
}

function stateFromServer(run: TextRun): RunState {
  return {
    text: run.currentText,
    fontFamily: run.editFontFamily,
    fontSize: run.editFontSize,
    bold: run.editBold,
    color: run.editColor,
    spans: run.editSpans ?? [],
    dx: run.dx,
    dy: run.dy,
  }
}

function untouched(state: RunState, run: TextRun): boolean {
  return (
    state.text === run.text &&
    state.fontFamily === null &&
    state.fontSize === null &&
    state.bold === null &&
    state.color === null &&
    state.spans.length === 0 &&
    state.dx === 0 &&
    state.dy === 0
  )
}

export function TextEditor({
  page,
  position,
  total,
  onNavigate,
  onClose,
  onEdited,
  onExport,
  onPrint,
  flatten,
  onFlattenChange,
  onOpenFind,
  installedFonts,
  unreadableFonts,
  onFontsReloaded,
  onRecognized,
  onRecognizeAll,
  ocrProgress,
  onStopRecognize,
  busy,
}: TextEditorProps) {
  const { documentId, pageIndex, key: pageKey } = page
  const [data, setData] = useState<PageText | null>(null)
  const [error, setError] = useState<string | null>(null)
  /** The last save failed because the server could not be reached: the edits are still here, so offer a retry. */
  const [retryable, setRetryable] = useState(false)
  const [status, setStatus] = useState('')
  /** Bumped after every save: asks the server for a fresh preview image. */
  const [revision, setRevision] = useState(0)
  /** The revision of the preview image that is on screen. */
  const [loadedRevision, setLoadedRevision] = useState(0)
  const [reloadToken, setReloadToken] = useState(0)
  const [fonts, setFonts] = useState<FontChoice[]>([])
  /** The server is still working out which edited lines have their original letters cut out. */
  const [removalPending, setRemovalPending] = useState(false)
  const removalSeq = useRef(0)
  const [fontHelpOpen, setFontHelpOpen] = useState(false)
  const [helpOpen, setHelpOpen] = useState(false)
  /** The short how-to-use tip shows until it is closed once. */
  const [tipOpen, setTipOpen] = useState(() => !editorTipSeen())
  const [fontsBusy, setFontsBusy] = useState(false)
  const [fontsMessage, setFontsMessage] = useState<string | null>(null)
  const [selected, setSelected] = useState<Selection>(null)
  /** The box that takes keyboard input; a selected box that is not being edited only moves and restyles. */
  const [editing, setEditing] = useState<Selection>(null)
  const [adding, setAdding] = useState(false)
  /** The lines text recognition found on this page (a scan's), outlined on the page while `showOcr`. */
  const [ocrLines, setOcrLines] = useState<OcrText[]>([])
  const [showOcr, setShowOcr] = useState(true)
  const [ocrBusy, setOcrBusy] = useState(false)
  const [ocrNote, setOcrNote] = useState<string | null>(null)
  /** The recognized line clicked on (its index in `ocrLines`): Ctrl+C copies it, Enter or a double click rewrites it. */
  const [ocrPicked, setOcrPicked] = useState<number | null>(null)
  const [ribbonTab, setRibbonTab] = useState('edit')
  /** The tool row can be folded away to give the page more room; picking a menu unfolds it. */
  const [ribbonOpen, setRibbonOpen] = useState(true)
  const selectRibbonTab = (id: string) => {
    setRibbonTab(id)
    setRibbonOpen(true)
  }
  const [guides, setGuides] = useState<Guides | null>(null)
  /** 'fit' follows the window width, 'page' shows the whole page; a number is a fixed zoom (1 = 100%). */
  const [zoomMode, setZoomMode] = useState<'fit' | 'page' | number>('fit')
  const [containerWidth, setContainerWidth] = useState(1000)
  const [containerHeight, setContainerHeight] = useState(800)
  const mainRef = useRef<HTMLElement>(null)
  const layerRef = useRef<HTMLDivElement>(null)
  const dataRef = useRef<PageText | null>(null)
  /**
   * What the user did to each edited line (absent = untouched) and the boxes they added. Held twice:
   * as state for drawing, and in a ref updated at the same moment, so a save that runs right after an
   * edit never sees a stale copy.
   */
  const [runStates, setRunStates] = useState(new Map<number, RunState>())
  const [added, setAdded] = useState<AddedText[]>([])
  const [images, setImages] = useState<AddedImage[]>([])
  const statesRef = useRef(runStates)
  const addedRef = useRef(added)
  const imagesRef = useRef(images)
  const imagesDirty = useRef(false)
  const imageInput = useRef<HTMLInputElement>(null)
  const putStates = useCallback((next: Map<number, RunState>) => {
    statesRef.current = next
    setRunStates(next)
  }, [])
  const putAdded = useCallback((next: AddedText[]) => {
    addedRef.current = next
    setAdded(next)
  }, [])
  const putImages = useCallback((next: AddedImage[]) => {
    imagesRef.current = next
    setImages(next)
  }, [])
  const pendingRuns = useRef(new Set<number>())
  const addedDirty = useRef(false)
  /** The look of the last box that was styled, so the next added box starts out the same. */
  const lastAddedLook = useRef({ fontFamily: null as string | null, fontSize: DEFAULT_ADDED_SIZE, bold: false, color: '#000000' })
  const saveTimer = useRef<number | undefined>(undefined)
  const queue = useRef<Promise<void>>(Promise.resolve())
  const lastSaveFailed = useRef(false)
  const pasteCount = useRef(0)
  const pasteTimer = useRef<number | undefined>(undefined)
  /** Undo/redo: whole-page snapshots taken before each action (states are immutable, so snapshots are cheap). */
  const historyRef = useRef(new History<Snapshot>(HISTORY_LIMIT, COALESCE_MS))
  const [history, setHistory] = useState({ undo: 0, redo: 0 })
  /** Reading a scanned page can take a while; say so instead of leaving a bare "loading". */
  const [slow, setSlow] = useState(false)
  /** Set after render so a save that fails for a refused edit can take the edit back. */
  const rollbackRef = useRef<() => void>(() => {})

  useEffect(() => {
    if (data || error) return
    const timer = window.setTimeout(() => setSlow(true), 2500)
    return () => window.clearTimeout(timer)
  }, [data, error])

  useEffect(() => {
    fetchFonts()
      .then(setFonts)
      .catch(() => setFonts([]))
  }, [])

  // The page is drawn at the window's width unless the user picked a zoom, so it never needs sideways scrolling.
  useEffect(() => {
    const el = mainRef.current
    if (!el) return
    const update = () => {
      setContainerWidth(el.clientWidth)
      setContainerHeight(el.clientHeight)
    }
    update()
    const observer = new ResizeObserver(update)
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  useEffect(() => {
    let cancelled = false
    fetchPageText(documentId, pageIndex)
      .then((loaded) => {
        if (cancelled) return
        putStates(new Map(loaded.runs.filter((r) => r.edited).map((r) => [r.index, stateFromServer(r)])))
        putAdded(loaded.added ?? [])
        putImages(loaded.images ?? [])
        pendingRuns.current.clear()
        addedDirty.current = false
        imagesDirty.current = false
        dataRef.current = loaded
        setData(loaded)
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : '텍스트를 불러오지 못했습니다')
      })
    return () => {
      cancelled = true
    }
  }, [documentId, pageIndex, reloadToken, putStates, putAdded, putImages])

  /** Bumped after every page was recognized: this page's lines are fetched again. */
  const [ocrToken, setOcrToken] = useState(0)
  useEffect(() => {
    let cancelled = false
    fetchPageOcr(documentId, pageIndex)
      .then((result) => {
        if (!cancelled) setOcrLines(result.lines)
      })
      // Only the outlines of recognized lines go missing; editing and recognizing again still work.
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [documentId, pageIndex, ocrToken])

  /**
   * After a save: which edited lines have their original letters cut out of the page and which are only
   * covered. The server works that out when it draws the page, so it is asked again - and only those flags
   * are taken over, so nothing the user is typing or moving is disturbed.
   */
  const refreshRemoval = useCallback(() => {
    const seq = ++removalSeq.current
    setRemovalPending(true)
    fetchPageText(documentId, pageIndex)
      .then((loaded) => {
        if (seq !== removalSeq.current) return // a newer save has asked again
        const flags = new Map(loaded.runs.map((r) => [r.index, r.originalRemoved]))
        const current = dataRef.current
        if (current) {
          const next = {
            ...current,
            runs: current.runs.map((r) => (flags.has(r.index) ? { ...r, originalRemoved: flags.get(r.index) === true } : r)),
          }
          dataRef.current = next
          setData(next)
        }
        setRemovalPending(false)
      })
      .catch(() => {
        if (seq === removalSeq.current) setRemovalPending(false)
      })
  }, [documentId, pageIndex])

  const flush = useCallback(() => {
    window.clearTimeout(saveTimer.current)
    const run = async () => {
      const current = dataRef.current
      if (!current) return
      const indices = Array.from(pendingRuns.current)
      pendingRuns.current.clear()
      const sendAdded = addedDirty.current
      addedDirty.current = false
      const sendImages = imagesDirty.current
      imagesDirty.current = false
      if (indices.length === 0 && !sendAdded && !sendImages) return
      setStatus('저장 중…')
      lastSaveFailed.current = false
      try {
        if (indices.length > 0) {
          const items: TextEditPayload[] = indices.map((index) => {
            const s = statesRef.current.get(index)
            return {
              index,
              text: s?.text ?? current.runs[index].text,
              fontFamily: s?.fontFamily ?? null,
              fontSize: s?.fontSize ?? null,
              bold: s?.bold ?? null,
              color: s?.color ?? null,
              spans: s?.spans ?? [],
              dx: s?.dx ?? 0,
              dy: s?.dy ?? 0,
            }
          })
          await saveTextEdits(documentId, pageIndex, items)
        }
        if (sendAdded) {
          await saveAddedTexts(
            documentId,
            pageIndex,
            addedRef.current.filter(nonEmpty),
          )
        }
        if (sendImages) {
          await saveAddedImages(documentId, pageIndex, imagesRef.current)
        }
        setRetryable(false)
        setStatus(savedLabel())
        setRevision((r) => r + 1)
        onEdited(pageKey)
        refreshRemoval()
      } catch (e) {
        lastSaveFailed.current = true
        setStatus('저장 실패')
        if (e instanceof ApiError && e.status >= 400 && e.status < 500) {
          // The server refused the change itself (a font without the characters, a size out of range):
          // take it back, so the screen never claims an edit that the server does not have.
          setError(`${e.message} — 방금 변경을 취소했습니다.`)
          setRetryable(false)
          rollbackRef.current()
        } else {
          // The server could not be reached: nothing is lost, the edits are still here to send again.
          indices.forEach((index) => pendingRuns.current.add(index))
          if (sendAdded) addedDirty.current = true
          if (sendImages) imagesDirty.current = true
          setError('서버에 저장하지 못했습니다. 입력한 내용은 화면에 그대로 있으니 다시 시도하세요.')
          setRetryable(true)
        }
      }
    }
    // Serialize saves so a slow request can never overwrite a newer one.
    queue.current = queue.current.then(run)
    return queue.current
  }, [documentId, pageIndex, pageKey, onEdited, refreshRemoval])

  const scheduleSave = useCallback(() => {
    window.clearTimeout(saveTimer.current)
    saveTimer.current = window.setTimeout(flush, 300)
  }, [flush])

  const syncHistory = useCallback(() => {
    setHistory({ undo: historyRef.current.undoCount, redo: historyRef.current.redoCount })
  }, [])

  const currentSnapshot = (): Snapshot => ({
    runs: statesRef.current,
    added: addedRef.current.filter(nonEmpty),
    images: imagesRef.current,
  })

  /** Call before changing anything: remembers the page as it is now, so Ctrl+Z can return to it. */
  const checkpoint = useCallback(
    (key: string) => {
      if (
        historyRef.current.record(key, {
          runs: statesRef.current,
          added: addedRef.current.filter(nonEmpty),
          images: imagesRef.current,
        })
      ) {
        syncHistory()
      }
    },
    [syncHistory],
  )

  /** Makes the page look like {@code snapshot} again and has the changes saved. */
  const restore = useCallback(
    (snapshot: Snapshot) => {
      const current = statesRef.current
      for (const index of new Set([...current.keys(), ...snapshot.runs.keys()])) {
        if (JSON.stringify(current.get(index)) !== JSON.stringify(snapshot.runs.get(index))) {
          pendingRuns.current.add(index)
        }
      }
      if (JSON.stringify(addedRef.current) !== JSON.stringify(snapshot.added)) addedDirty.current = true
      if (JSON.stringify(imagesRef.current) !== JSON.stringify(snapshot.images)) imagesDirty.current = true
      putStates(snapshot.runs)
      putAdded(snapshot.added)
      putImages(snapshot.images)
      setSelected((s) => {
        if (s?.kind === 'added' && !snapshot.added.some((b) => b.id === s.id)) return null
        if (s?.kind === 'image' && !snapshot.images.some((b) => b.id === s.id)) return null
        return s
      })
      setEditing(null)
      scheduleSave()
    },
    [putStates, putAdded, putImages, scheduleSave],
  )

  const undo = useCallback(() => {
    const snapshot = historyRef.current.undo(currentSnapshot())
    if (!snapshot) {
      setStatus('되돌릴 내용이 없습니다')
      return
    }
    restore(snapshot)
    syncHistory()
  }, [restore, syncHistory])

  const redo = useCallback(() => {
    const snapshot = historyRef.current.redo(currentSnapshot())
    if (!snapshot) {
      setStatus('다시 실행할 내용이 없습니다')
      return
    }
    restore(snapshot)
    syncHistory()
  }, [restore, syncHistory])

  useEffect(() => {
    rollbackRef.current = undo
  })

  const updateRun = useCallback(
    (run: TextRun, patch: Partial<RunState>) => {
      checkpoint(`run:${run.index}:${Object.keys(patch).join('+')}`)
      const states = new Map(statesRef.current)
      const next = { ...(states.get(run.index) ?? blankState(run)), ...patch }
      // A choice equal to the original is no choice at all; keeping it would only stop the line from
      // being redrawn glyph by glyph in its own font.
      if (next.fontSize !== null && sameSize(next.fontSize, run.fontSize)) next.fontSize = null
      if (next.bold !== null && next.bold === run.bold) next.bold = null
      if (next.color !== null && next.color.toLowerCase() === run.color.toLowerCase()) next.color = null
      if (untouched(next, run)) states.delete(run.index)
      else states.set(run.index, next)
      putStates(states)
      pendingRuns.current.add(run.index)
      scheduleSave()
    },
    [checkpoint, putStates, scheduleSave],
  )

  const commitRunText = useCallback(
    (run: TextRun, text: string) => {
      const s = statesRef.current.get(run.index)
      const before = s?.text ?? run.text
      // The bold and coloured stretches stay on the letters they were put on.
      if (text !== before) updateRun(run, { text, spans: remapSpans(s?.spans ?? [], before, text) })
    },
    [updateRun],
  )

  /**
   * The letters picked inside the line being typed in: bold, colour, size and underline then go to just those.
   * Held twice, like the other states: in a ref for the handlers, as state so the style bar shows their look.
   */
  const [picked, setPicked] = useState<PickedRange | null>(null)
  const textRange = useRef<PickedRange | null>(null)
  const putRange = useCallback((range: PickedRange | null) => {
    const was = textRange.current
    if (was?.key === range?.key && was?.start === range?.start && was?.end === range?.end) return
    textRange.current = range
    setPicked(range)
  }, [])
  const onTextRange = useCallback(
    (run: TextRun, range: { start: number; end: number } | null) => putRange(range ? { key: `r${run.index}`, ...range } : null),
    [putRange],
  )
  const onAddedRange = useCallback(
    (box: AddedText, range: { start: number; end: number } | null) => putRange(range ? { key: `a${box.id}`, ...range } : null),
    [putRange],
  )
  useEffect(() => {
    putRange(null)
  }, [selected, putRange])

  /** The line's own look, which the spans are laid over. */
  function lineLookOf(run: TextRun, s: RunState | undefined) {
    return { bold: s?.bold ?? run.bold, color: s?.color ?? run.color, size: s?.fontSize ?? run.fontSize }
  }

  /** Lays a look over just the picked letters of a line (partial formatting). */
  function formatPicked(run: TextRun, range: { start: number; end: number }, patch: LookPatch) {
    const s = statesRef.current.get(run.index)
    const before = s?.text ?? run.text
    // What is in the box is what the user sees, typed text not yet committed included.
    const box = layerRef.current?.querySelector<HTMLElement>(`[data-index="${run.index}"]`)
    const text = box?.textContent ?? before
    const start = Math.min(range.start, text.length)
    const end = Math.min(range.end, text.length)
    if (end <= start) return
    const line = lineLookOf(run, s)
    const base = remapSpans(s?.spans ?? [], before, text)
    // Bold and underline switch: on unless every picked letter has it already.
    const next: LookPatch = { ...patch }
    if (patch.bold !== undefined) next.bold = toggleFor(base, start, end, 'bold', line)
    if (patch.underline !== undefined) next.underline = toggleFor(base, start, end, 'underline', line)
    updateRun(run, { text, spans: formatRange(base, start, end, text.length, next, line) })
  }

  /** The look of an added box as a whole, which its spans are laid over. */
  const boxLookOf = (box: AddedText) => ({ bold: box.bold, color: box.color ?? '#000000', size: box.fontSize })

  /** Lays a look over just the picked letters of an added box. */
  function formatPickedAdded(box: AddedText, range: { start: number; end: number }, patch: LookPatch) {
    // What is in the box is what the user sees, typed text not yet committed included.
    const el = layerRef.current?.querySelector<HTMLElement>(`[data-added="${box.id}"]`)
    const text = el?.textContent ?? box.text
    const start = Math.min(range.start, text.length)
    const end = Math.min(range.end, text.length)
    if (end <= start) return
    const line = boxLookOf(box)
    const base = remapSpans(box.spans, box.text, text)
    const next: LookPatch = { ...patch }
    if (patch.bold !== undefined) next.bold = toggleFor(base, start, end, 'bold', line)
    if (patch.underline !== undefined) next.underline = toggleFor(base, start, end, 'underline', line)
    updateAdded(box.id, { text, spans: formatRange(base, start, end, text.length, next, line) })
  }

  const moveRun = useCallback((run: TextRun, dx: number, dy: number) => updateRun(run, { dx, dy }), [updateRun])

  const updateAdded = useCallback(
    (id: string, patch: Partial<AddedText>) => {
      checkpoint(`added:${id}:${Object.keys(patch).join('+')}`)
      const boxes = addedRef.current.map((b) => (b.id === id ? { ...b, ...patch } : b))
      putAdded(boxes)
      const touched = boxes.find((b) => b.id === id)
      if (touched && ('fontFamily' in patch || 'fontSize' in patch || 'bold' in patch || 'color' in patch)) {
        lastAddedLook.current = {
          fontFamily: touched.fontFamily,
          fontSize: touched.fontSize,
          bold: touched.bold,
          color: touched.color ?? '#000000',
        }
      }
      addedDirty.current = true
      scheduleSave()
    },
    [checkpoint, putAdded, scheduleSave],
  )

  const removeAdded = useCallback(
    (id: string) => {
      const existed = addedRef.current.find((b) => b.id === id)
      if (existed && existed.text.trim() !== '') checkpoint(`remove:${id}:${Date.now()}`)
      putAdded(addedRef.current.filter((b) => b.id !== id))
      setSelected((s) => (s?.kind === 'added' && s.id === id ? null : s))
      setEditing((s) => (s?.kind === 'added' && s.id === id ? null : s))
      if (existed && existed.text.trim() !== '') {
        addedDirty.current = true
        scheduleSave()
      }
    },
    [checkpoint, putAdded, scheduleSave],
  )

  const commitAddedText = useCallback(
    (box: AddedText, text: string, next: Element | null) => {
      if (text.trim() === '') {
        // An empty box is dropped - unless focus only went to the style bar to pick its font first.
        if (!next?.closest('.te-stylebar')) removeAdded(box.id)
        else updateAdded(box.id, { text, spans: remapSpans(box.spans, box.text, text) })
        return
      }
      // The formatted parts stay on the letters they were put on.
      if (text !== box.text) updateAdded(box.id, { text, spans: remapSpans(box.spans, box.text, text) })
    },
    [removeAdded, updateAdded],
  )

  const updateImage = useCallback(
    (id: string, patch: Partial<AddedImage>) => {
      checkpoint(`image:${id}:${Object.keys(patch).join('+')}`)
      putImages(imagesRef.current.map((b) => (b.id === id ? { ...b, ...patch } : b)))
      imagesDirty.current = true
      scheduleSave()
    },
    [checkpoint, putImages, scheduleSave],
  )

  const removeImage = useCallback(
    (id: string) => {
      checkpoint(`remove-image:${id}:${Date.now()}`)
      putImages(imagesRef.current.filter((b) => b.id !== id))
      setSelected((s) => (s?.kind === 'image' && s.id === id ? null : s))
      imagesDirty.current = true
      scheduleSave()
    },
    [checkpoint, putImages, scheduleSave],
  )

  function duplicateImage(id: string) {
    const source = imagesRef.current.find((b) => b.id === id)
    if (!source) return
    checkpoint(`dup-image:${Date.now()}`)
    const copy: AddedImage = { ...source, id: newId(), x: round(source.x + PASTE_OFFSET), y: round(source.y - PASTE_OFFSET) }
    putImages([...imagesRef.current, copy])
    setSelected({ kind: 'image', id: copy.id })
    imagesDirty.current = true
    scheduleSave()
  }

  /** Uploads a picture and puts it in the middle of the page, at its own size unless that is too big for the page. */
  async function addImageFromFile(file: File) {
    const current = dataRef.current
    if (!current) return
    setStatus('그림을 올리는 중…')
    try {
      const uploaded = await uploadImage(documentId, file)
      // 96 dpi pixels to points, then no wider than a share of the page, whatever the picture's own size.
      const naturalWidth = (uploaded.width * 72) / 96
      const width = round(Math.max(8, Math.min(naturalWidth, current.pageWidth * FIRST_IMAGE_SHARE)))
      const height = round((width * uploaded.height) / uploaded.width)
      const box: AddedImage = {
        id: newId(),
        imageId: uploaded.imageId,
        x: round(current.originX + (current.pageWidth - width) / 2),
        y: round(current.originY + (current.pageHeight - height) / 2),
        width,
        height,
        keyOutWhite: false,
      }
      checkpoint(`add-image:${box.id}`)
      putImages([...imagesRef.current, box])
      setSelected({ kind: 'image', id: box.id })
      setEditing(null)
      imagesDirty.current = true
      scheduleSave()
      setStatus('')
    } catch (e) {
      setStatus('')
      setError(e instanceof Error ? e.message : '그림을 올리지 못했습니다')
    }
  }

  /** Selects a box; a box that was being typed in is left first, so its text is kept. */
  const select = useCallback((next: Selection) => {
    const active = document.activeElement
    if (active instanceof HTMLElement && active.closest('[data-index], [data-added]')) active.blur()
    setSelected(next)
    setEditing(null)
    setOcrPicked(null)
  }, [])

  function patchSelected(patch: {
    fontFamily?: string | null
    fontSize?: number
    bold?: boolean
    color?: string
    underline?: boolean
  }) {
    const current = dataRef.current
    if (!selected || !current) return
    if (selected.kind === 'run') {
      const run = current.runs[selected.index]
      const range = textRange.current
      const look: LookPatch = { bold: patch.bold, color: patch.color, size: patch.fontSize, underline: patch.underline }
      const looks = Object.values(look).some((v) => v !== undefined)
      if (range && range.key === `r${run.index}` && looks) {
        formatPicked(run, range, look) // letters are picked: only they change
        return
      }
      if (patch.underline !== undefined) {
        // An underline has no line-wide setting: with nothing picked it goes under the whole line.
        const text = statesRef.current.get(run.index)?.text ?? run.text
        if (text.length > 0) formatPicked(run, { start: 0, end: text.length }, { underline: true })
        return
      }
      // A change to the whole line also takes over what the stretches with a look of their own said about it.
      let spans = statesRef.current.get(run.index)?.spans ?? []
      if (patch.bold !== undefined) spans = clearField(spans, 'bold')
      if (patch.color !== undefined) spans = clearField(spans, 'color')
      if (patch.fontSize !== undefined) spans = clearField(spans, 'size')
      updateRun(run, { ...patch, spans })
    } else if (selected.kind === 'added') {
      const box = addedRef.current.find((b) => b.id === selected.id)
      if (!box) return
      const range = textRange.current
      const look: LookPatch = { bold: patch.bold, color: patch.color, size: patch.fontSize, underline: patch.underline }
      if (range && range.key === `a${box.id}` && Object.values(look).some((v) => v !== undefined)) {
        formatPickedAdded(box, range, look) // letters are picked: only they change
        return
      }
      if (patch.underline !== undefined) {
        // With nothing picked the underline goes under the whole text.
        if (box.text.length > 0) formatPickedAdded(box, { start: 0, end: box.text.length }, { underline: true })
        return
      }
      // A change to the whole box also takes over what the stretches with a look of their own said about it.
      let spans = box.spans
      if (patch.bold !== undefined) spans = clearField(spans, 'bold')
      if (patch.color !== undefined) spans = clearField(spans, 'color')
      if (patch.fontSize !== undefined) spans = clearField(spans, 'size')
      updateAdded(box.id, { ...patch, spans })
    }
  }

  function resetSelectedRun() {
    const current = dataRef.current
    if (selected?.kind !== 'run' || !current) return
    checkpoint(`reset:${selected.index}:${Date.now()}`)
    const states = new Map(statesRef.current)
    states.delete(selected.index)
    putStates(states)
    pendingRuns.current.add(selected.index)
    scheduleSave()
  }

  /**
   * Redraws the selected line entirely in the installed font that stands in for its own font, so
   * characters the page font lacks no longer stand out from the ones it has.
   */
  function unifySelectedRun() {
    const current = dataRef.current
    if (selected?.kind !== 'run' || !current) return
    const family = installedFamily(current.runs[selected.index].fontFamily)
    if (family) patchSelected({ fontFamily: family })
  }

  /** The installed family a line's CSS family stands for, if this PC has it (a pasted line is drawn in it). */
  function installedFamily(name: string | null): string | null {
    if (!name) return null
    const key = normalize(name)
    const found = fonts.find((f) => normalize(f.name) === key) ?? fonts.find((f) => normalize(f.label).includes(key))
    return found?.name ?? null
  }

  /** The selected line or box as a text box: its text as it reads now, in the look it has now, where it is now. */
  function describeSelection(): Omit<AddedText, 'id'> | null {
    const current = dataRef.current
    if (!selected || !current) return null
    if (selected.kind === 'run') {
      const run = current.runs[selected.index]
      const s = statesRef.current.get(run.index)
      return {
        text: s?.text ?? run.text,
        x: run.x + (s?.dx ?? 0),
        y: run.y + (s?.dy ?? 0),
        fontFamily: installedFamily(s?.fontFamily ?? run.fontFamily),
        fontSize: s?.fontSize ?? run.fontSize,
        bold: s?.bold ?? run.bold,
        color: s?.color ?? run.color,
        spans: s?.spans ?? [], // the bold, coloured, resized or underlined parts come along
      }
    }
    const box = addedRef.current.find((b) => b.id === selected.id)
    return box
      ? {
          text: box.text,
          x: box.x,
          y: box.y,
          fontFamily: box.fontFamily,
          fontSize: box.fontSize,
          bold: box.bold,
          color: box.color,
          spans: box.spans,
          ...(box.rotation ? { rotation: box.rotation } : {}),
        }
      : null
  }

  function addBoxFrom(look: Omit<AddedText, 'id'>, offset: number) {
    checkpoint(`paste:${Date.now()}`)
    const box: AddedText = { id: newId(), ...look, x: round(look.x + offset), y: round(look.y - offset) }
    putAdded([...addedRef.current, box])
    addedDirty.current = true
    setSelected({ kind: 'added', id: box.id })
    setEditing(null)
    scheduleSave()
  }

  function copySelection() {
    if (selected?.kind === 'image') {
      const picture = imagesRef.current.find((b) => b.id === selected.id)
      if (!picture) return
      const { id: _id, ...look } = picture
      clipboard = { kind: 'image', look, from: { documentId, pageIndex } }
      pasteCount.current = 0
      setStatus('그림을 복사했습니다 (Ctrl+V로 붙여넣기, 다른 페이지에도 됩니다)')
      return
    }
    const look = describeSelection()
    if (!look) return
    clipboard = { kind: 'text', look, from: { documentId, pageIndex } }
    pasteCount.current = 0
    setStatus('줄을 복사했습니다 (Ctrl+V로 붙여넣기)')
  }

  function pasteClipboard() {
    if (!clipboard) {
      setStatus('복사한 줄이 없습니다')
      return
    }
    // While the page is still being read its list of boxes is about to be replaced: a paste now would be lost.
    if (!dataRef.current || dataRef.current.rotated) {
      setStatus('페이지를 불러오는 중입니다. 잠시 후 다시 붙여넣어 주세요')
      return
    }
    // On the page it came from a paste lands beside the original; on another page it takes the same spot.
    const samePage = clipboard.from.documentId === documentId && clipboard.from.pageIndex === pageIndex
    pasteCount.current += 1
    const offset = samePage ? PASTE_OFFSET * pasteCount.current : 0
    if (clipboard.kind === 'image') {
      void pasteImage(clipboard, offset)
    } else {
      addBoxFrom(clipboard.look, offset)
    }
  }

  /**
   * Puts a copied picture on this page. A picture belongs to the document it was uploaded to, so one that came
   * from another document is fetched from there and uploaded again to this one.
   */
  async function pasteImage(copied: Extract<Copied, { kind: 'image' }>, offset: number) {
    setStatus('그림을 붙여넣는 중…')
    try {
      let imageId = copied.look.imageId
      if (copied.from.documentId !== documentId) {
        const response = await fetch(imageUrl(copied.from.documentId, imageId))
        if (!response.ok) throw new Error('복사한 그림을 찾을 수 없습니다. 원래 문서가 지워졌을 수 있습니다.')
        const blob = await response.blob()
        imageId = (await uploadImage(documentId, new File([blob], 'picture', { type: blob.type }))).imageId
      }
      const box: AddedImage = {
        ...copied.look,
        id: newId(),
        imageId,
        x: round(copied.look.x + offset),
        y: round(copied.look.y - offset),
      }
      checkpoint(`paste-image:${box.id}`)
      putImages([...imagesRef.current, box])
      setSelected({ kind: 'image', id: box.id })
      setEditing(null)
      imagesDirty.current = true
      scheduleSave()
      setStatus('')
    } catch (e) {
      setStatus('')
      setError(e instanceof Error ? e.message : '그림을 붙여넣지 못했습니다')
    }
  }

  function duplicateSelection() {
    if (selected?.kind === 'image') {
      duplicateImage(selected.id)
      return
    }
    const look = describeSelection()
    if (look) addBoxFrom(look, PASTE_OFFSET)
  }

  function handleLayerPointerDown(e: PointerEvent<HTMLDivElement>) {
    const current = dataRef.current
    if (e.target !== e.currentTarget || !current || scale <= 0) return
    if (adding) {
      const rect = e.currentTarget.getBoundingClientRect()
      const look = lastAddedLook.current
      const box: AddedText = {
        id: newId(),
        text: '',
        x: round(current.originX + (e.clientX - rect.left) / scale),
        y: round(current.originY + current.pageHeight - (e.clientY - rect.top) / scale - look.fontSize),
        fontFamily: look.fontFamily,
        fontSize: look.fontSize,
        bold: look.bold,
        color: look.color,
        spans: [],
      }
      e.preventDefault()
      putAdded([...addedRef.current, box])
      setSelected({ kind: 'added', id: box.id })
      setEditing({ kind: 'added', id: box.id })
      setAdding(false)
      return
    }
    // Finish typing first, so the text of the box that is being left is kept, then drop the selection.
    const active = document.activeElement
    if (active instanceof HTMLElement && active.closest('[data-index], [data-added]')) active.blur()
    setSelected(null)
    setEditing(null)
    setOcrPicked(null)
    putAdded(addedRef.current.filter(nonEmpty))
  }

  async function applyAll() {
    const current = dataRef.current
    if (!current) return
    layerRef.current?.querySelectorAll<HTMLElement>('[data-index]').forEach((el) => {
      const run = current.runs[Number(el.dataset.index)]
      if (run) commitRunText(run, el.textContent ?? '')
    })
    layerRef.current?.querySelectorAll<HTMLElement>('[data-added]').forEach((el) => {
      const box = addedRef.current.find((b) => b.id === el.dataset.added)
      if (box && (el.textContent ?? '').trim() !== '' && el.textContent !== box.text) {
        updateAdded(box.id, { text: el.textContent ?? '' })
      }
    })
    await flush()
    // Edits are also sent as soon as a box loses focus, so there is often nothing left to send;
    // refresh the label anyway so pressing the button visibly confirms that everything is applied.
    if (!lastSaveFailed.current) setStatus(savedLabel())
  }

  async function exportAll() {
    await applyAll()
    if (lastSaveFailed.current) return
    await onExport()
  }

  /** Prints the whole document as it would be exported, once what was typed here is applied. */
  async function printAll() {
    await applyAll()
    if (lastSaveFailed.current) return
    await onPrint()
  }

  const close = useCallback(async () => {
    await flush()
    onClose()
  }, [flush, onClose])

  async function navigate(delta: -1 | 1) {
    await flush()
    onNavigate(delta)
  }

  /** Recognized lines a text box rewrites (its cover lies over their middle): their outline gives way to the box. */
  const rewritten = useMemo(() => {
    const covers = added.flatMap((b) => (b.cover ? [b.cover] : []))
    return new Set(
      ocrLines.flatMap((line, i) => {
        const mx = line.x + line.width / 2
        const my = line.y + line.height / 2
        const covered = covers.some((c) => mx >= c.x && mx <= c.x + c.width && my >= c.y && my <= c.y + c.height)
        return covered ? [i] : []
      }),
    )
  }, [added, ocrLines])

  /** Copies the picked recognized line, or with none picked every line of the page, top to bottom. */
  async function copyRecognized() {
    const lines = ocrPicked !== null ? [ocrLines[ocrPicked]] : ocrLines.filter((_, i) => !rewritten.has(i))
    if (lines.length === 0 || lines[0] === undefined) return
    const copied = await copyText(lines.map((l) => l.text).join('\n'))
    setStatus(copied ? `인식한 글자 ${lines.length}줄을 복사했습니다` : '복사하지 못했습니다')
  }

  /**
   * Turns recognized line `index` into a text box that can be edited: the server works out its size and colours
   * and a patch hiding the scanned letters. The box is added like any other (Ctrl+Z takes it back).
   */
  async function rewriteLine(index: number) {
    if (!editable) return
    setOcrPicked(null)
    setStatus('고쳐 쓸 수 있게 바꾸는 중…')
    try {
      const look = await ocrTextBox(documentId, pageIndex, index)
      checkpoint(`rewrite:${index}:${Date.now()}`)
      const box: AddedText = { ...look, id: newId(), spans: look.spans ?? [] }
      putAdded([...addedRef.current, box])
      addedDirty.current = true
      setSelected({ kind: 'added', id: box.id })
      setEditing({ kind: 'added', id: box.id })
      scheduleSave()
      setStatus('')
    } catch (e) {
      setStatus('')
      setError(e instanceof Error ? e.message : '인식한 글자를 고쳐 쓸 수 있게 바꾸지 못했습니다')
    }
  }

  /** Recognizes every page of the list (this one too), then shows what this page has. */
  async function recognizeEveryPage() {
    await flush()
    setOcrNote(null)
    const message = await onRecognizeAll()
    setOcrPicked(null)
    setShowOcr(true)
    setOcrToken((n) => n + 1)
    if (message) setOcrNote(message)
  }

  /** Recognizes the text of this page (a second or two), outlines what was found and tells the page list. */
  async function recognizeThisPage() {
    setOcrBusy(true)
    setOcrNote(null)
    setError(null)
    let found: OcrText[] = []
    try {
      const summary = await recognizeAll(
        [page],
        async (id, index) => {
          const result = await recognizePage(id, index)
          found = result.lines
          return result
        },
        () => {},
        () => false,
      )
      setOcrLines(found)
      setOcrPicked(null)
      setShowOcr(true)
      const unsure = found.filter((l) => l.score < UNSURE).length
      setOcrNote(
        ocrMessage(summary) +
          (found.length > 0 ? ' 점선 줄을 클릭해 Ctrl+C로 복사하거나, 두 번 클릭해 고쳐 쓸 수 있습니다.' : '') +
          (unsure > 0 ? ` 주황색 ${unsure}줄은 인식이 불확실하니 확인하세요.` : ''),
      )
      onRecognized(pageKey, found.length)
    } catch (e) {
      setError(e instanceof Error ? e.message : '글자를 인식하지 못했습니다')
    } finally {
      setOcrBusy(false)
    }
  }

  async function resetPage() {
    if (!window.confirm('이 페이지의 모든 텍스트 수정과 추가한 텍스트를 되돌릴까요? (Ctrl+Z로 다시 살릴 수 있습니다)')) return
    checkpoint(`reset-page:${Date.now()}`)
    window.clearTimeout(saveTimer.current)
    pendingRuns.current.clear()
    addedDirty.current = false
    imagesDirty.current = false
    try {
      await resetTextEdits(documentId, pageIndex)
      setSelected(null)
      setEditing(null)
      setRevision((r) => r + 1)
      setReloadToken((t) => t + 1)
      setStatus('되돌림')
      onEdited(pageKey)
    } catch (e) {
      setError(e instanceof Error ? e.message : '되돌리지 못했습니다')
    }
  }

  // Closing the tab while a change has not reached the server would lose it; everything else is kept.
  useEffect(() => {
    const warn = (e: BeforeUnloadEvent) => {
      if (pendingRuns.current.size > 0 || addedDirty.current || imagesDirty.current || lastSaveFailed.current) {
        e.preventDefault()
        e.returnValue = ''
      }
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [])

  const fit = data ? fitZoom(containerWidth, data.pageWidth) : 1
  const zoom =
    zoomMode === 'fit'
      ? fit
      : zoomMode === 'page'
        ? data
          ? fitPageZoom(containerWidth, containerHeight, data.pageWidth, data.pageHeight)
          : 1
        : zoomMode
  const missingFonts = useMemo(() => missingFontNames(data?.runs ?? []), [data])
  // Of the fonts the page lacks, those the PC does have - but as .otf files, which the app cannot read.
  const otfOnly = useMemo(
    () => missingFonts.filter((font) => otherFormatFamily(font, unreadableFonts) !== null),
    [missingFonts, unreadableFonts],
  )

  /**
   * Reads the installed fonts again, after the user installed one: sends what is still unsaved first,
   * then asks the page's lines and the preview again, since they now may be drawn in the new font.
   */
  async function reloadInstalledFonts(): Promise<void> {
    if (fontsBusy) return
    setFontsBusy(true)
    try {
      await flush()
      const info = await reloadFonts()
      setFonts(await fetchFonts())
      setFontsMessage(reloadMessage(installedFonts, info.installedFonts, info.unreadableFonts ?? []))
      onFontsReloaded(info)
      setReloadToken((t) => t + 1)
      setRevision((r) => r + 1)
    } catch (e) {
      setFontsMessage(e instanceof Error ? e.message : '글꼴을 다시 읽지 못했습니다')
    } finally {
      setFontsBusy(false)
    }
  }

  const scale = data ? zoom * PX_PER_PT_AT_100 : 0
  const dpi = previewDpi(scale, window.devicePixelRatio || 1)
  const stale = loadedRevision !== revision

  const zoomRef = useRef(zoom)
  useEffect(() => {
    zoomRef.current = zoom
  })
  const changeZoom = useCallback((direction: 1 | -1) => setZoomMode(zoomStep(zoomRef.current, direction)), [])

  // Ctrl + mouse wheel zooms the page, as in other viewers (needs a non-passive listener to stop the browser's own zoom).
  useEffect(() => {
    const el = mainRef.current
    if (!el) return
    const onWheel = (e: WheelEvent) => {
      if (!e.ctrlKey) return
      e.preventDefault()
      changeZoom(e.deltaY < 0 ? 1 : -1)
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  }, [changeZoom])

  /** Where every other box sits, so a box that is dragged can be pulled onto the same left edge or baseline. */
  const lines = useMemo(
    () => [
      ...(data?.runs ?? []).map((r) => ({
        id: `r${r.index}`,
        x: r.x + (runStates.get(r.index)?.dx ?? 0),
        y: r.y + (runStates.get(r.index)?.dy ?? 0),
      })),
      ...added.map((b) => ({ id: `a${b.id}`, x: b.x, y: b.y })),
    ],
    [data, runStates, added],
  )
  const targetsFor = (id: string): SnapTargets => {
    const others = lines.filter((l) => l.id !== id)
    return { xs: others.map((l) => l.x), ys: others.map((l) => l.y) }
  }

  let view: StyleView | null = null
  if (data && selected?.kind === 'run') {
    const run = data.runs[selected.index]
    const s = runStates.get(run.index)
    // Letters with a look of their own are drawn with the page's glyphs too: only a change to the whole line is not.
    const restyled = s !== undefined && (s.fontFamily !== null || s.fontSize !== null || s.bold !== null || s.color !== null)
    // With letters picked the bar shows how the first of them looks, since that is what a change goes to.
    const lineLook = lineLookOf(run, s)
    const text = s?.text ?? run.text
    const range = picked?.key === `r${run.index}` && picked.start < text.length ? picked : null
    const shown = range ? lookAt(s?.spans ?? [], range.start, lineLook) : lineLook
    const underlined =
      text.length > 0 && !toggleFor(s?.spans ?? [], range?.start ?? 0, Math.min(range?.end ?? text.length, text.length), 'underline', lineLook)
    view = {
      kind: 'run',
      label: `줄 ${run.index + 1}`,
      sourceFont: run.sourceFont,
      autoFamily: run.fontFamily,
      family: s?.fontFamily ?? null,
      size: shown.size,
      bold: shown.bold,
      color: shown.color,
      underline: underlined,
      canReset: s !== undefined,
      look: s === undefined ? null : !restyled && run.glyphReuse ? 'original' : 'substitute',
      // A character the page's font lacks is drawn in another font only when the page's own font is not
      // installed here (or has no name to find it by, like a Type 3 font); an installed one supplies it.
      mixedChars:
        s !== undefined &&
        !restyled &&
        run.glyphReuse &&
        (run.sourceFontMissing ||
          (run.sourceFont.startsWith('Type3') && !(run.matchSimilarity !== null && run.matchSimilarity >= SIMILAR_ENOUGH)))
          ? foreignChars(s.text, run.ownChars)
          : '',
      missingFont: run.sourceFontMissing ? run.sourceFont : null,
      original: s === undefined || removalPending ? null : run.originalRemoved ? 'removed' : 'covered',
      similarity: run.matchSimilarity,
      partial: s?.spans.length ?? 0,
    }
  } else if (selected?.kind === 'added') {
    const box = added.find((b) => b.id === selected.id)
    if (box) {
      // With letters picked the bar shows how the first of them looks, since that is what a change goes to.
      const boxLook = boxLookOf(box)
      const range = picked?.key === `a${box.id}` && picked.start < box.text.length ? picked : null
      const shown = range ? lookAt(box.spans, range.start, boxLook) : boxLook
      const underlined =
        box.text.length > 0 &&
        !toggleFor(box.spans, range?.start ?? 0, Math.min(range?.end ?? box.text.length, box.text.length), 'underline', boxLook)
      view = {
        kind: 'added',
        label: '새 텍스트',
        sourceFont: null,
        autoFamily: DEFAULT_ADDED_FAMILY,
        family: box.fontFamily,
        size: shown.size,
        bold: shown.bold,
        color: shown.color,
        underline: underlined,
        canReset: false,
        look: null,
        mixedChars: '',
        missingFont: null,
        original: null,
        similarity: null,
        partial: box.spans.length,
      }
    }
  }

  const pickedImage = selected?.kind === 'image' ? (images.find((b) => b.id === selected.id) ?? null) : null
  const imageView: ImageView | null = pickedImage
    ? { width: pickedImage.width, height: pickedImage.height, keyOutWhite: pickedImage.keyOutWhite }
    : null

  /** A new width for the selected picture, its proportions kept and its top left corner where it was. */
  function resizeSelectedImage(width: number) {
    if (!pickedImage || !(width >= 8 && width <= 5000)) return
    const height = round((width * pickedImage.height) / pickedImage.width)
    updateImage(pickedImage.id, { width: round(width), height, y: round(pickedImage.y + pickedImage.height - height) })
  }

  const editable = data !== null && !data.rotated

  // What became of the original letters of the edited lines (once the server has said so).
  let removedCount = 0
  let coveredCount = 0
  if (data && !removalPending) {
    for (const index of runStates.keys()) {
      const run = data.runs[index]
      if (!run) continue
      if (run.originalRemoved) removedCount++
      else coveredCount++
    }
  }

  /**
   * Keyboard shortcuts. The listener is installed once and calls the handler of the latest render, so
   * the handler always sees the current selection. Letters are matched by key position (code), so
   * they work while the keyboard is in Korean input mode too.
   */
  const shortcut = useRef<(e: globalThis.KeyboardEvent) => void>(() => {})
  useEffect(() => {
    shortcut.current = (e) => {
      if (e.isComposing || e.keyCode === 229) return // a Hangul syllable is still being composed
      const el = e.target instanceof HTMLElement ? e.target : null
      const box = el?.closest<HTMLElement>('[data-index], [data-added]') ?? null
      const inField = el?.closest('input, select, textarea, .find-dialog, .fp-popup') != null
      const ctrl = e.ctrlKey || e.metaKey
      const code = keyCode(e)
      if (el?.closest('.find-dialog')) return // the find dialog has its own keys
      if (helpOpen || fontHelpOpen) {
        // A help window is open: Esc closes it (and only it), the other keys wait.
        if (e.key === 'Escape') {
          e.preventDefault()
          setHelpOpen(false)
          setFontHelpOpen(false)
        }
        return
      }

      // Text typed into a box since it got focus keeps the browser's own text undo; the page-level
      // undo takes over as soon as there is nothing typed to take back.
      const committed =
        box === null
          ? ''
          : box.dataset.index !== undefined
            ? (statesRef.current.get(Number(box.dataset.index))?.text ?? dataRef.current?.runs[Number(box.dataset.index)]?.text ?? '')
            : (addedRef.current.find((b) => b.id === box.dataset.added)?.text ?? '')
      const typedInBox = box !== null && (box.textContent ?? '') !== committed
      const textSelected = window.getSelection()?.isCollapsed === false

      if (ctrl && code === 'KeyS') {
        e.preventDefault()
        if (e.shiftKey) void applyAll()
        else void exportAll()
        return
      }
      if (ctrl && code === 'KeyP') {
        // The document, not this screen, is what gets printed.
        e.preventDefault()
        void printAll()
        return
      }
      if (ctrl && (code === 'KeyZ' || code === 'KeyY')) {
        if (inField || typedInBox) return
        e.preventDefault()
        if (code === 'KeyY' || e.shiftKey) redo()
        else undo()
        return
      }
      // Ctrl+C copies the line, unless text is selected inside the box being typed in: that is a text copy.
      if (ocrPicked !== null && !inField && !box && (ctrl ? code === 'KeyC' : e.key === 'Enter')) {
        e.preventDefault()
        if (ctrl) void copyRecognized()
        else void rewriteLine(ocrPicked)
        return
      }
      if (ctrl && code === 'KeyC' && selected && !inField && !(box && textSelected)) {
        e.preventDefault()
        copySelection()
        return
      }
      if (ctrl && code === 'KeyV' && !inField && !box) {
        // Not cancelled: the browser may still send a paste event, the only way to see a picture on the clipboard.
        // When none comes (nothing focused) the line copied with Ctrl+C is pasted.
        window.clearTimeout(pasteTimer.current)
        pasteTimer.current = window.setTimeout(pasteClipboard, PASTE_WAIT_MS)
        return
      }
      if (ctrl && code === 'KeyD' && selected) {
        e.preventDefault() // the browser would bookmark the page
        duplicateSelection()
        return
      }
      if (ctrl && (e.key === '=' || e.key === '+' || code === 'NumpadAdd')) {
        e.preventDefault()
        changeZoom(1)
        return
      }
      if (ctrl && (e.key === '-' || code === 'NumpadSubtract')) {
        e.preventDefault()
        changeZoom(-1)
        return
      }
      if (ctrl && (e.key === '0' || code === 'Numpad0')) {
        e.preventDefault()
        setZoomMode('fit')
        return
      }
      if (ctrl && (e.key === '9' || code === 'Numpad9')) {
        e.preventDefault()
        setZoomMode('page')
        return
      }
      if (e.key === 'Escape') {
        putRange(null)
        if (adding) setAdding(false)
        else if (box || inField) el?.blur()
        else if (editing) setEditing(null)
        else if (selected) setSelected(null)
        else if (ocrPicked !== null) setOcrPicked(null)
        else void close()
        return
      }
      if (ctrl && code === 'KeyB' && view && !inField) {
        e.preventDefault()
        patchSelected({ bold: !view.bold })
        return
      }
      if (ctrl && code === 'KeyU' && view && !inField) {
        e.preventDefault() // the browser would show the page source
        patchSelected({ underline: true })
        return
      }
      const bracket = code === 'BracketRight' || e.key === ']' ? 1 : code === 'BracketLeft' || e.key === '[' ? -1 : 0
      if (ctrl && bracket !== 0 && view && !inField) {
        e.preventDefault()
        patchSelected({ fontSize: round(Math.min(500, Math.max(1, view.size + bracket))) })
        return
      }
      if (box || inField) return

      if (!ctrl && !e.altKey && (e.key === '?' || (code === 'Slash' && e.shiftKey))) {
        e.preventDefault()
        setHelpOpen(true)
        return
      }
      if (ctrl && !e.altKey && (e.key === 'ArrowLeft' || e.key === 'ArrowRight')) {
        e.preventDefault()
        void navigate(e.key === 'ArrowLeft' ? -1 : 1)
        return
      }
      if (!ctrl && !e.altKey && !e.shiftKey && code === 'KeyT' && editable) {
        e.preventDefault()
        setAdding((a) => !a)
        return
      }
      if ((e.key === 'Enter' || e.key === 'F2') && selected) {
        e.preventDefault()
        setEditing(selected)
        return
      }
      if ((e.key === 'Delete' || e.key === 'Backspace') && selected?.kind === 'added') {
        e.preventDefault()
        removeAdded(selected.id)
        return
      }
      if ((e.key === 'Delete' || e.key === 'Backspace') && selected?.kind === 'image') {
        e.preventDefault()
        removeImage(selected.id)
        return
      }
      const arrow = ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].indexOf(e.key)
      if (arrow >= 0 && !ctrl && !e.altKey && selected) {
        e.preventDefault()
        const step = e.shiftKey ? 10 : 1
        const dx = e.key === 'ArrowLeft' ? -step : e.key === 'ArrowRight' ? step : 0
        const dy = e.key === 'ArrowUp' ? step : e.key === 'ArrowDown' ? -step : 0
        if (selected.kind === 'run') {
          const run = dataRef.current?.runs[selected.index]
          const s = statesRef.current.get(selected.index)
          if (run) updateRun(run, { dx: round((s?.dx ?? 0) + dx), dy: round((s?.dy ?? 0) + dy) })
        } else if (selected.kind === 'image') {
          const target = imagesRef.current.find((b) => b.id === selected.id)
          if (target) updateImage(target.id, { x: round(target.x + dx), y: round(target.y + dy) })
        } else {
          const target = addedRef.current.find((b) => b.id === selected.id)
          if (target) updateAdded(target.id, { x: round(target.x + dx), y: round(target.y + dy) })
        }
      }
    }
  })
  useEffect(() => {
    const listener = (e: globalThis.KeyboardEvent) => shortcut.current(e)
    window.addEventListener('keydown', listener)
    return () => window.removeEventListener('keydown', listener)
  }, [])

  /**
   * Ctrl+V: a picture on the clipboard (a screenshot, a copied signature) goes onto the page; otherwise the line
   * copied with Ctrl+C is pasted. Text typed into a box or a field is left to the browser.
   */
  const pasteHandler = useRef<(e: ClipboardEvent) => void>(() => {})
  useEffect(() => {
    pasteHandler.current = (e) => {
      window.clearTimeout(pasteTimer.current) // the paste arrived: the key press needs no fallback
      const el = e.target instanceof HTMLElement ? e.target : null
      // A paste lands on the page's text selection, which stays inside a line after it was left: only a box that
      // has the focus counts as one being typed in.
      const typedBox = el?.closest('[data-index], [data-added]') ?? null
      const typing = typedBox !== null && typedBox === document.activeElement
      if (typing || el?.closest('input, select, textarea, .find-dialog, .fp-popup')) return
      if (helpOpen || fontHelpOpen || !editable) return
      const picture = Array.from(e.clipboardData?.files ?? []).find((f) => f.type.startsWith('image/'))
      e.preventDefault()
      if (picture) void addImageFromFile(picture)
      else pasteClipboard()
    }
  })
  useEffect(() => {
    const listener = (e: ClipboardEvent) => pasteHandler.current(e)
    window.addEventListener('paste', listener)
    return () => window.removeEventListener('paste', listener)
  }, [])

  return (
    <div className="te-overlay" role="dialog" aria-modal="true" aria-label="텍스트 편집">
      <header className="te-header rb-header">
        <div className="rb-top">
          <button type="button" className="rb-back" onClick={close} title="페이지 목록으로 돌아갑니다 (Esc)">
            <ArrowLeft size={16} aria-hidden="true" /> 페이지 목록
          </button>
          <span className="te-title" title={page.sourceFileName}>
            PDF 문서 · {page.sourceFileName} · {pageIndex + 1}쪽
          </span>
          <span className="te-pager" role="group" aria-label="쪽 이동">
            <button type="button" onClick={() => void navigate(-1)} disabled={position <= 0} title="이전 쪽 (Ctrl+←)" aria-label="이전 쪽">
              <ChevronLeft size={16} aria-hidden="true" />
            </button>
            <span className="te-sub">
              {position + 1} / {total}
            </span>
            <button
              type="button"
              onClick={() => void navigate(1)}
              disabled={position >= total - 1}
              title="다음 쪽 (Ctrl+→)"
              aria-label="다음 쪽"
            >
              <ChevronRight size={16} aria-hidden="true" />
            </button>
          </span>
          <span className="te-spacer" />
          <span className="te-status" aria-live="polite">
            {status}
          </span>
          <button type="button" onClick={() => void printAll()} disabled={busy} title="인쇄 (Ctrl+P) — 고친 내용과 가림 영역을 반영한 문서 전체">
            <Printer size={16} aria-hidden="true" /> 인쇄
          </button>
          <button type="button" className="primary" onClick={exportAll} disabled={busy} title="PDF로 내보내기 (Ctrl+S)">
            <FileDown size={16} aria-hidden="true" /> PDF로 내보내기
          </button>
        </div>
        <div className="rb-menubar">
          <RibbonTabs tabs={EDITOR_TABS} active={ribbonTab} onChange={selectRibbonTab} />
          <span className="te-spacer" />
          <button
            type="button"
            className="rb-collapse"
            onClick={() => setRibbonOpen((open) => !open)}
            title={ribbonOpen ? '도구 상자 접기 (메뉴를 누르면 다시 펼쳐집니다)' : '도구 상자 펼치기'}
            aria-label={ribbonOpen ? '도구 상자 접기' : '도구 상자 펼치기'}
            aria-expanded={ribbonOpen}
          >
            {ribbonOpen ? <ChevronUp size={16} aria-hidden="true" /> : <ChevronDown size={16} aria-hidden="true" />}
          </button>
        </div>

        {ribbonOpen && ribbonTab === 'insert' && (
          <RibbonPanel label="입력">
            <RibbonGroup label="넣기">
              <RibbonButton
                icon={<Type />}
                label="텍스트 추가"
                title="누른 뒤 페이지의 원하는 위치를 클릭하세요 (T)"
                onClick={() => setAdding((a) => !a)}
                disabled={!editable}
                pressed={adding}
              />
              <RibbonButton
                icon={<ImagePlus />}
                label="그림 추가"
                title="도장·서명·로고 같은 그림 파일(PNG, JPG)을 페이지에 넣습니다"
                onClick={() => imageInput.current?.click()}
                disabled={!editable}
              />
            </RibbonGroup>
          </RibbonPanel>
        )}

        {ribbonOpen && ribbonTab === 'format' && (
          <RibbonPanel label="서식">
            <RibbonGroup label="글자 모양">
              <RibbonButton
                icon={<Bold />}
                label="굵게 하기"
                title="선택한 글자(또는 줄 전체)를 굵게 하거나 굵게를 풉니다 (Ctrl+B)"
                onClick={() => view && patchSelected({ bold: !view.bold })}
                disabled={!editable || view === null}
                pressed={view?.bold ?? false}
              />
              <RibbonButton
                icon={<Underline />}
                label="밑줄 긋기"
                title="선택한 글자(또는 줄 전체)에 밑줄을 긋거나 지웁니다 (Ctrl+U)"
                onClick={() => patchSelected({ underline: true })}
                disabled={!editable || view === null}
                pressed={view?.underline ?? false}
              />
              <RibbonButton
                icon={<ALargeSmall />}
                label="글꼴 통일"
                title="줄 전체를 한 가지 대체 글꼴로 다시 그려 글자 모양을 통일합니다"
                onClick={unifySelectedRun}
                disabled={!editable || view?.kind !== 'run' || !view.mixedChars}
              />
              <RibbonButton
                icon={<RotateCcw />}
                label="원위치"
                title="선택한 줄의 글자·모양·위치를 원래대로 되돌립니다"
                onClick={resetSelectedRun}
                disabled={!editable || view?.kind !== 'run' || !view.canReset}
              />
            </RibbonGroup>
          </RibbonPanel>
        )}

        {ribbonOpen && ribbonTab === 'file' && (
          <RibbonPanel label="파일">
            <RibbonGroup label="문서">
              <RibbonButton icon={<ArrowLeft />} label="페이지 목록" title="페이지 목록으로 돌아갑니다" onClick={close} />
            </RibbonGroup>
            <RibbonGroup label="내보내기">
              <RibbonButton
                icon={<FileDown />}
                label="PDF 저장"
                title="모든 페이지를 수정 내용과 함께 PDF로 내려받습니다 (Ctrl+S)"
                onClick={() => void exportAll()}
                disabled={busy}
              />
              <RibbonButton
                icon={<FileImage />}
                label="이미지로 변환"
                title="켜면 각 페이지를 그림으로 바꿔 내보냅니다. 수정 전 글자가 파일에서 완전히 사라지지만 검색·복사는 할 수 없습니다."
                onClick={() => onFlattenChange(!flatten)}
                pressed={flatten}
              />
              <RibbonButton
                icon={<Printer />}
                label="문서 인쇄"
                title="고친 내용과 가림 영역을 반영한 문서 전체를 인쇄합니다 (Ctrl+P)"
                onClick={() => void printAll()}
                disabled={busy}
              />
            </RibbonGroup>
            <RibbonGroup label="이 페이지">
              <RibbonButton
                icon={<CheckCheck />}
                label="지금 반영"
                title="고친 내용을 바로 서버에 반영합니다 (Ctrl+Shift+S)"
                onClick={() => void applyAll()}
                disabled={!editable}
              />
              <RibbonButton
                icon={<RotateCcw />}
                label="페이지 되돌리기"
                title="이 페이지에서 고친 것을 모두 원래대로 되돌립니다"
                onClick={() => void resetPage()}
                disabled={!editable}
                danger
              />
            </RibbonGroup>
          </RibbonPanel>
        )}

        {ribbonOpen && ribbonTab === 'edit' && (
          <RibbonPanel label="편집">
            <RibbonGroup label="넣기">
              <RibbonButton
                icon={<Type />}
                label="텍스트 추가"
                title="누른 뒤 페이지의 원하는 위치를 클릭하세요 (T)"
                onClick={() => setAdding((a) => !a)}
                disabled={!editable}
                pressed={adding}
              />
              <RibbonButton
                icon={<ImagePlus />}
                label="그림 추가"
                title="도장·서명·로고 같은 그림 파일(PNG, JPG)을 페이지에 넣습니다"
                onClick={() => imageInput.current?.click()}
                disabled={!editable}
              />
            </RibbonGroup>
            <RibbonGroup label="선택한 항목">
              <RibbonButton
                icon={<CopyPlus />}
                label="복제"
                title="선택한 줄·상자·그림을 하나 더 만듭니다 (Ctrl+D)"
                onClick={duplicateSelection}
                disabled={!editable || (view === null && pickedImage === null)}
              />
              <RibbonButton
                icon={<Trash />}
                label="삭제"
                title="선택한 텍스트 상자나 그림을 지웁니다 (Delete)"
                onClick={() => {
                  if (selected?.kind === 'added') removeAdded(selected.id)
                  else if (pickedImage) removeImage(pickedImage.id)
                }}
                disabled={!editable || (selected?.kind !== 'added' && pickedImage === null)}
                danger
              />
              <RibbonButton
                icon={<RotateCcw />}
                label="원위치"
                title="선택한 줄의 글자·모양·위치를 원래대로 되돌립니다"
                onClick={resetSelectedRun}
                disabled={!editable || view?.kind !== 'run' || !view.canReset}
              />
              <RibbonButton
                icon={<ALargeSmall />}
                label="글꼴 통일"
                title="줄 전체를 한 가지 대체 글꼴로 다시 그려 글자 모양을 통일합니다"
                onClick={unifySelectedRun}
                disabled={!editable || view?.kind !== 'run' || !view.mixedChars}
              />
            </RibbonGroup>
            <RibbonGroup label="찾기">
              <RibbonButton icon={<Search />} label="찾아 바꾸기" title="모든 페이지에서 글자를 찾아 바꿉니다 (Ctrl+F)" onClick={onOpenFind} />
            </RibbonGroup>
            <RibbonGroup label="글자 인식">
              <RibbonButton
                icon={<ScanText />}
                label={ocrBusy ? '인식 중…' : '글자 인식(OCR)'}
                title="스캔한 이 쪽의 글자를 읽어, 내보낸 PDF에서 검색·복사할 수 있게 합니다 (2초쯤, 이 PC 안에서만 처리). 여러 쪽은 페이지 목록의 글자 인식으로 한 번에 할 수 있습니다."
                onClick={() => void recognizeThisPage()}
                disabled={ocrBusy || busy}
              />
              <RibbonButton
                icon={<Files />}
                label={ocrProgress ? `${ocrProgress.done}/${ocrProgress.total}쪽…` : '모든 쪽 인식'}
                title="페이지 목록의 모든 쪽을 차례로 인식합니다 (쪽당 2초쯤). 글자가 이미 있는 자리는 건너뜁니다."
                onClick={() => void recognizeEveryPage()}
                disabled={ocrBusy || busy}
              />
              <RibbonButton
                icon={<Eye />}
                label="인식 표시"
                title="인식한 글자 줄을 페이지 위에 점선으로 표시합니다"
                onClick={() => {
                  setShowOcr((show) => !show)
                  setOcrPicked(null)
                }}
                disabled={ocrLines.length === 0}
                pressed={showOcr && ocrLines.length > 0}
              />
              <RibbonButton
                icon={<Copy />}
                label="글자 복사"
                title="점선 줄을 클릭해 고른 줄을, 고르지 않았으면 이 쪽에서 인식한 글자를 모두 복사합니다 (Ctrl+C)"
                onClick={() => void copyRecognized()}
                disabled={ocrLines.length === rewritten.size}
              />
              <RibbonButton
                icon={<PenLine />}
                label="고쳐 쓰기"
                title="고른 점선 줄을 고칠 수 있는 글자로 바꿉니다 (두 번 클릭 또는 Enter). 스캔의 글자는 바탕색으로 덮입니다."
                onClick={() => ocrPicked !== null && void rewriteLine(ocrPicked)}
                disabled={!editable || ocrPicked === null}
              />
            </RibbonGroup>
          </RibbonPanel>
        )}

        {ribbonOpen && ribbonTab === 'view' && (
          <RibbonPanel label="보기">
            <RibbonGroup label="화면 크기">
              <RibbonButton icon={<ZoomIn />} label="확대" title="확대 (Ctrl++)" onClick={() => changeZoom(1)} />
              <RibbonButton icon={<ZoomOut />} label="축소" title="축소 (Ctrl+−)" onClick={() => changeZoom(-1)} />
              <RibbonButton
                icon={<MoveHorizontal />}
                label="폭 맞춤"
                title="페이지를 창 폭에 맞춥니다 (Ctrl+0)"
                onClick={() => setZoomMode('fit')}
                pressed={zoomMode === 'fit'}
              />
              <RibbonButton
                icon={<Scan />}
                label="쪽 맞춤"
                title="쪽 전체가 창 안에 보이게 맞춥니다 (Ctrl+9)"
                onClick={() => setZoomMode('page')}
                pressed={zoomMode === 'page'}
              />
            </RibbonGroup>
            <RibbonGroup label="쪽 이동">
              <RibbonButton icon={<ChevronLeft />} label="이전 쪽" title="이전 쪽으로 (Ctrl+←)" onClick={() => void navigate(-1)} disabled={position <= 0} />
              <RibbonButton
                icon={<ChevronRight />}
                label="다음 쪽"
                title="다음 쪽으로 (Ctrl+→)"
                onClick={() => void navigate(1)}
                disabled={position >= total - 1}
              />
            </RibbonGroup>
          </RibbonPanel>
        )}

        {ribbonOpen && ribbonTab === 'tools' && (
          <RibbonPanel label="도구">
            <RibbonGroup label="글꼴">
              <RibbonButton
                icon={<RefreshCw />}
                label={fontsBusy ? '읽는 중…' : '글꼴 다시 읽기'}
                title="글꼴을 새로 설치했다면 눌러서 설치된 글꼴을 다시 읽습니다 (앱을 다시 켤 필요 없음)"
                onClick={() => void reloadInstalledFonts()}
                disabled={fontsBusy}
              />
              <RibbonButton
                icon={<FileSearch />}
                label="없는 글꼴 받기"
                title="이 페이지에서 쓰는데 PC에 없는 글꼴과 구하는 방법을 보여 줍니다"
                onClick={() => setFontHelpOpen(true)}
              />
            </RibbonGroup>
            <RibbonGroup label="도움말">
              <RibbonButton icon={<CircleQuestionMark />} label="도움말" title="사용 방법과 단축키 (?)" onClick={() => setHelpOpen(true)} />
            </RibbonGroup>
          </RibbonPanel>
        )}
        <input
          ref={imageInput}
          type="file"
          accept="image/png,image/jpeg,image/gif,image/bmp"
          hidden
          data-testid="image-input"
          onChange={(e) => {
            const file = e.target.files?.[0]
            e.target.value = '' // the same file can be picked again
            if (file) void addImageFromFile(file)
          }}
        />
      </header>

      {editable && (
        <StyleBar
          view={view}
          fonts={fonts}
          onFamily={(family) => patchSelected({ fontFamily: family })}
          onSize={(size) => patchSelected({ fontSize: round(Math.min(500, Math.max(1, size))) })}
          onBold={(bold) => patchSelected({ bold })}
          onUnderline={() => patchSelected({ underline: true })}
          onColor={(color) => patchSelected({ color })}
          onReloadFonts={() => void reloadInstalledFonts()}
          fontsBusy={fontsBusy}
          onDuplicate={duplicateSelection}
          image={imageView}
          onImageWidth={resizeSelectedImage}
          onImageKeyOut={(keyOutWhite) => pickedImage && updateImage(pickedImage.id, { keyOutWhite })}
          onImageDelete={() => pickedImage && removeImage(pickedImage.id)}
          leading={
            <>
              <button type="button" onClick={() => void printAll()} disabled={busy} title="인쇄 (Ctrl+P)" aria-label="인쇄">
                <Printer size={16} aria-hidden="true" />
              </button>
              <button type="button" onClick={undo} disabled={history.undo === 0} title="실행 취소 (Ctrl+Z)" aria-label="실행 취소">
                <Undo2 size={16} aria-hidden="true" />
              </button>
              <button type="button" onClick={redo} disabled={history.redo === 0} title="다시 실행 (Ctrl+Y)" aria-label="다시 실행">
                <Redo2 size={16} aria-hidden="true" />
              </button>
            </>
          }
        />
      )}

      {tipOpen && editable && (
        <div className="te-tip" role="note">
          <span>
            <b>사용 방법</b> 클릭 = 선택 · 한 번 더 클릭 또는 Enter = 글자 수정 · 끌기 = 이동 · 위쪽 막대 = 글꼴·크기·굵기·색 ·{' '}
            <kbd>?</kbd> = 도움말과 단축키
          </span>
          <button type="button" onClick={() => setHelpOpen(true)}>
            도움말 보기
          </button>
          <button
            type="button"
            onClick={() => {
              markEditorTipSeen()
              setTipOpen(false)
            }}
            aria-label="안내 닫기 (다시 보지 않기)"
            title="닫으면 다시 나타나지 않습니다. 도움말은 위쪽 ? 버튼에서 볼 수 있습니다"
          >
            ✕
          </button>
        </div>
      )}

      {ocrProgress && (
        <div className="te-tip te-ocr-note" role="status">
          <span>
            모든 쪽 글자 인식 중… {ocrProgress.done}/{ocrProgress.total}쪽
          </span>
          <button type="button" onClick={onStopRecognize}>
            중지
          </button>
        </div>
      )}

      {ocrNote && !ocrProgress && (
        <div className="te-tip te-ocr-note" role="status">
          <span>{ocrNote}</span>
          <button type="button" onClick={() => setOcrNote(null)} aria-label="닫기">
            ✕
          </button>
        </div>
      )}

      {error && (
        <div className="error-banner te-error">
          <span>{error}</span>
          {retryable && (
            <button type="button" onClick={() => void flush()}>
              다시 시도
            </button>
          )}
          <button type="button" onClick={() => setError(null)} aria-label="닫기">
            ✕
          </button>
        </div>
      )}

      {missingFonts.length > 0 && !data?.rotated && (
        <p className="te-font-notice" role="status">
          ⚠ 이 페이지에서 쓰는 글꼴 {missingFonts.length}개가 이 PC에 없습니다: <b>{missingFonts.map(fontBaseName).join(', ')}</b>.
          이미 쓰인 글자는 그대로이고, 새로 입력하는 글자만 비슷한 다른 글꼴로 그려집니다.{' '}
          {otfOnly.length > 0 && (
            <>
              <b>{otfOnly.map(fontBaseName).join(', ')}</b>은(는) 이 PC에 .otf 형식으로만 설치되어 있어 이 앱이 읽지 못합니다. 같은
              글꼴의 <b>.ttf</b> 버전을 설치해 주세요.{' '}
            </>
          )}
          <button type="button" onClick={() => setFontHelpOpen(true)}>
            받는 방법
          </button>
          <button type="button" onClick={() => void reloadInstalledFonts()} disabled={fontsBusy}>
            {fontsBusy ? '읽는 중…' : '글꼴 다시 읽기'}
          </button>
        </p>
      )}

      <main className="te-main" ref={mainRef}>
        {!data && !error && (
          <p className="te-notice">
            불러오는 중…
            {slow && (
              <>
                <br />
                글자를 읽는 데 시간이 걸리고 있습니다.
              </>
            )}
          </p>
        )}
        {data?.rotated && (
          <p className="te-notice">
            회전된 상태로 저장된 문서입니다. 새 버전은 올릴 때 회전을 풀어 주므로, 이 PDF를 다시 올리면 텍스트를 편집할 수 있습니다.
          </p>
        )}
        {data && !data.rotated && data.runs.length === 0 && ocrLines.length > 0 && (
          <p className="te-notice">
            스캔한 쪽입니다. 인식한 글자 {ocrLines.length}줄이 점선으로 표시됩니다: 클릭해 고른 뒤 Ctrl+C로 복사하고, 두 번
            클릭하면 고쳐 쓸 수 있습니다. 내보낸 PDF에서도 검색·복사됩니다.
          </p>
        )}
        {data && !data.rotated && data.runs.length === 0 && ocrLines.length === 0 && (
          <p className="te-notice">
            이 페이지에는 고칠 수 있는 기존 글자가 없습니다 (빈 페이지이거나 스캔 이미지일 수 있습니다). 위의 ‘텍스트 추가’로
            글자를 넣을 수 있고, 스캔한 쪽이면 ‘글자 인식(OCR)’으로 내보낸 PDF에서 글자를 검색할 수 있게 합니다.
          </p>
        )}
        {data && !data.rotated && (
          <div className="te-canvas">
            <img
              src={previewUrl(documentId, pageIndex, revision, dpi)}
              alt={`${page.sourceFileName} ${pageIndex + 1}쪽`}
              style={{ width: data.pageWidth * scale }}
              onLoad={(e) => {
                const loaded = Number(new URL(e.currentTarget.currentSrc).searchParams.get('rev'))
                setLoadedRevision(Number.isFinite(loaded) ? loaded : revision)
              }}
            />
            {scale > 0 && (
              <div
                className={`te-layer${adding ? ' adding' : ''}${stale ? ' stale' : ''}`}
                ref={layerRef}
                onPointerDown={handleLayerPointerDown}
              >
                {/* Until the new preview arrives, the patch a rewritten line puts over the scan is shown here. */}
                {added.map((box) => {
                  if (!box.cover) return null
                  const place = {
                    left: (box.cover.x - data.originX) * scale,
                    top: (data.pageHeight - (box.cover.y + box.cover.height - data.originY)) * scale,
                    width: box.cover.width * scale,
                    height: box.cover.height * scale,
                  }
                  return box.cover.patch ? (
                    <img
                      key={`cover${box.id}`}
                      className="te-cover"
                      alt=""
                      src={`data:image/png;base64,${box.cover.patch}`}
                      style={place}
                    />
                  ) : (
                    <div key={`cover${box.id}`} className="te-cover" style={{ ...place, background: box.cover.color }} />
                  )
                })}
                {showOcr &&
                  ocrLines.map((line, i) =>
                    rewritten.has(i) ? null : (
                      <div
                        key={`ocr${i}`}
                        className={`te-ocr-line${ocrPicked === i ? ' picked' : ''}${line.score < UNSURE ? ' unsure' : ''}`}
                        data-ocr={i}
                        title={`${line.text}${line.score < UNSURE ? ` (인식이 불확실합니다: 확신도 ${Math.round(line.score * 100)}%)` : ''}\n클릭: 고르기 (Ctrl+C 복사) · 두 번 클릭: 고쳐 쓰기`}
                        style={{
                          left: (line.x - data.originX) * scale,
                          top: (data.pageHeight - (line.y + line.height - data.originY)) * scale,
                          width: line.width * scale,
                          height: line.height * scale,
                        }}
                        onPointerDown={(e) => {
                          e.stopPropagation()
                          select(null)
                          setOcrPicked(i)
                        }}
                        onDoubleClick={() => void rewriteLine(i)}
                      />
                    ),
                  )}
                {data.runs.map((run) => (
                  <RunBox
                    key={run.index}
                    run={run}
                    state={runStates.get(run.index)}
                    scale={scale}
                    data={data}
                    selected={selected?.kind === 'run' && selected.index === run.index}
                    editing={editing?.kind === 'run' && editing.index === run.index}
                    targets={targetsFor(`r${run.index}`)}
                    onSelect={() => select({ kind: 'run', index: run.index })}
                    onStartEdit={() => setEditing({ kind: 'run', index: run.index })}
                    onStopEdit={() => setEditing(null)}
                    onCommitText={commitRunText}
                    onMoveTo={moveRun}
                    onGuides={setGuides}
                    onSelection={onTextRange}
                  />
                ))}
                {images.map((box) => (
                  <ImageBox
                    key={box.id}
                    box={box}
                    documentId={documentId}
                    scale={scale}
                    data={data}
                    selected={selected?.kind === 'image' && selected.id === box.id}
                    onSelect={() => select({ kind: 'image', id: box.id })}
                    onMoveTo={(b, x, y) => updateImage(b.id, { x, y })}
                    onResize={(b, x, y, width, height) => updateImage(b.id, { x, y, width, height })}
                    onRemove={(b) => removeImage(b.id)}
                  />
                ))}
                {added.map((box) => (
                  <AddedBox
                    key={box.id}
                    box={box}
                    scale={scale}
                    data={data}
                    selected={selected?.kind === 'added' && selected.id === box.id}
                    editing={editing?.kind === 'added' && editing.id === box.id}
                    targets={targetsFor(`a${box.id}`)}
                    onSelect={() => select({ kind: 'added', id: box.id })}
                    onStartEdit={() => setEditing({ kind: 'added', id: box.id })}
                    onStopEdit={() => setEditing(null)}
                    onCommitText={commitAddedText}
                    onSelection={onAddedRange}
                    onMoveTo={(b, x, y) => updateAdded(b.id, { x, y })}
                    onRemove={(b) => removeAdded(b.id)}
                    onGuides={setGuides}
                  />
                ))}
                {guides?.x != null && <div className="te-guide v" style={{ left: (guides.x - data.originX) * scale }} />}
                {guides?.y != null && (
                  <div className="te-guide h" style={{ top: (data.pageHeight - (guides.y - data.originY)) * scale }} />
                )}
              </div>
            )}
          </div>
        )}
      </main>

      {coveredCount > 0 && (
        <p className="te-redact-note">
          ⚠ 수정한 줄 중 {coveredCount}개는 원래 글자가 PDF 안에 가려진 채 남아 있어서, 다른 프로그램에서 검색하거나 복사하면 보일 수
          있습니다. 완전히 없애려면 내보낼 때 ‘이미지로 변환’을 켜세요.
        </p>
      )}
      {coveredCount === 0 && removedCount > 0 && (
        <p className="te-redact-note te-redact-ok">✓ 수정한 줄의 원래 글자는 PDF에서 지워졌습니다 (가리기만 한 것이 아닙니다).</p>
      )}
      <footer className="te-statusbar" aria-label="상태 표시줄">
        <span className="te-group">
          <button type="button" onClick={() => void navigate(-1)} disabled={position <= 0} title="이전 페이지 (Ctrl+←)" aria-label="이전 페이지">
            <ChevronLeft size={14} aria-hidden="true" />
          </button>
          <span className="te-sub">
            {position + 1} / {total}쪽
          </span>
          <button
            type="button"
            onClick={() => void navigate(1)}
            disabled={position >= total - 1}
            title="다음 페이지 (Ctrl+→)"
            aria-label="다음 페이지"
          >
            <ChevronRight size={14} aria-hidden="true" />
          </button>
        </span>
        <span className="te-statusbar-sep" aria-hidden="true" />
        {editable ? (
          <StyleInfo view={view} image={imageView} fonts={fonts} onFontHelp={() => setFontHelpOpen(true)} />
        ) : (
          <span className="te-style-info" />
        )}
        <ZoomControl zoom={zoom} onZoomIn={() => changeZoom(1)} onZoomOut={() => changeZoom(-1)} onZoomFit={() => setZoomMode('fit')} onZoomPage={() => setZoomMode('page')} />
      </footer>
      {helpOpen && <HelpDialog onClose={() => setHelpOpen(false)} />}
      {fontHelpOpen && (
        <MissingFontDialog
          fonts={missingFonts}
          unreadableFonts={unreadableFonts}
          message={fontsMessage}
          busy={fontsBusy}
          onReload={() => void reloadInstalledFonts()}
          onClose={() => setFontHelpOpen(false)}
        />
      )}
    </div>
  )
}
