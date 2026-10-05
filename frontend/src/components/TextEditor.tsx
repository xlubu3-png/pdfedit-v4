import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { PointerEvent } from 'react'
import {
  ApiError,
  fetchFonts,
  fetchPageText,
  previewUrl,
  resetTextEdits,
  saveAddedTexts,
  saveTextEdits,
} from '../api'
import type { TextEditPayload } from '../api'
import { foreignChars } from '../fontMix'
import { History } from '../history'
import { keyCode } from '../keys'
import type { SnapTargets } from '../snap'
import type { AddedText, FontChoice, PageItem, PageText, RunState, TextRun } from '../types'
import { fitZoom, previewDpi, PX_PER_PT_AT_100, zoomStep } from '../zoom'
import { StyleBar } from './StyleBar'
import type { StyleView } from './StyleBar'
import { AddedBox, RunBox } from './TextBoxes'
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
  /** Export as a picture of each page: nothing of the original text stays in the file. */
  flatten: boolean
  onFlattenChange: (flatten: boolean) => void
  /** Opens the find-and-replace dialog. */
  onOpenFind: () => void
  busy: boolean
}

/** What the server draws an added box in when no font is chosen. */
const DEFAULT_ADDED_FAMILY = '맑은 고딕'
const DEFAULT_ADDED_SIZE = 12
/** How far a duplicate or a paste lands from the original, in points. */
const PASTE_OFFSET = 12
const HISTORY_LIMIT = 100
/** Changes to the same thing within this many ms (typing in the size box, nudging with arrows) are one undo step. */
const COALESCE_MS = 800

type Selection = { kind: 'run'; index: number } | { kind: 'added'; id: string } | null

/** Everything undo can bring back: what was done to each line, and the boxes that were added. */
interface Snapshot {
  runs: Map<number, RunState>
  added: AddedText[]
}

/** A copied line or box, ready to be pasted (also onto another page: this outlives the editor). */
interface Copied extends Omit<AddedText, 'id'> {
  from: { documentId: string; pageIndex: number }
}
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
  return { text: run.text, fontFamily: null, fontSize: null, bold: null, color: null, dx: 0, dy: 0 }
}

function stateFromServer(run: TextRun): RunState {
  return {
    text: run.currentText,
    fontFamily: run.editFontFamily,
    fontSize: run.editFontSize,
    bold: run.editBold,
    color: run.editColor,
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
  flatten,
  onFlattenChange,
  onOpenFind,
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
  const [selected, setSelected] = useState<Selection>(null)
  /** The box that takes keyboard input; a selected box that is not being edited only moves and restyles. */
  const [editing, setEditing] = useState<Selection>(null)
  const [adding, setAdding] = useState(false)
  const [guides, setGuides] = useState<Guides | null>(null)
  /** 'fit' follows the window width; a number is a fixed zoom (1 = 100%). */
  const [zoomMode, setZoomMode] = useState<'fit' | number>('fit')
  const [containerWidth, setContainerWidth] = useState(1000)
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
  const statesRef = useRef(runStates)
  const addedRef = useRef(added)
  const putStates = useCallback((next: Map<number, RunState>) => {
    statesRef.current = next
    setRunStates(next)
  }, [])
  const putAdded = useCallback((next: AddedText[]) => {
    addedRef.current = next
    setAdded(next)
  }, [])
  const pendingRuns = useRef(new Set<number>())
  const addedDirty = useRef(false)
  /** The look of the last box that was styled, so the next added box starts out the same. */
  const lastAddedLook = useRef({ fontFamily: null as string | null, fontSize: DEFAULT_ADDED_SIZE, bold: false, color: '#000000' })
  const saveTimer = useRef<number | undefined>(undefined)
  const queue = useRef<Promise<void>>(Promise.resolve())
  const lastSaveFailed = useRef(false)
  const pasteCount = useRef(0)
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
    const update = () => setContainerWidth(el.clientWidth)
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
        pendingRuns.current.clear()
        addedDirty.current = false
        dataRef.current = loaded
        setData(loaded)
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : '텍스트를 불러오지 못했습니다')
      })
    return () => {
      cancelled = true
    }
  }, [documentId, pageIndex, reloadToken, putStates, putAdded])

  const flush = useCallback(() => {
    window.clearTimeout(saveTimer.current)
    const run = async () => {
      const current = dataRef.current
      if (!current) return
      const indices = Array.from(pendingRuns.current)
      pendingRuns.current.clear()
      const sendAdded = addedDirty.current
      addedDirty.current = false
      if (indices.length === 0 && !sendAdded) return
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
        setRetryable(false)
        setStatus(savedLabel())
        setRevision((r) => r + 1)
        onEdited(pageKey)
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
          setError('서버에 저장하지 못했습니다. 입력한 내용은 화면에 그대로 있으니 다시 시도하세요.')
          setRetryable(true)
        }
      }
    }
    // Serialize saves so a slow request can never overwrite a newer one.
    queue.current = queue.current.then(run)
    return queue.current
  }, [documentId, pageIndex, pageKey, onEdited])

  const scheduleSave = useCallback(() => {
    window.clearTimeout(saveTimer.current)
    saveTimer.current = window.setTimeout(flush, 300)
  }, [flush])

  const syncHistory = useCallback(() => {
    setHistory({ undo: historyRef.current.undoCount, redo: historyRef.current.redoCount })
  }, [])

  const currentSnapshot = (): Snapshot => ({ runs: statesRef.current, added: addedRef.current.filter(nonEmpty) })

  /** Call before changing anything: remembers the page as it is now, so Ctrl+Z can return to it. */
  const checkpoint = useCallback(
    (key: string) => {
      if (historyRef.current.record(key, { runs: statesRef.current, added: addedRef.current.filter(nonEmpty) })) {
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
      putStates(snapshot.runs)
      putAdded(snapshot.added)
      setSelected((s) => (s?.kind === 'added' && !snapshot.added.some((b) => b.id === s.id) ? null : s))
      setEditing(null)
      scheduleSave()
    },
    [putStates, putAdded, scheduleSave],
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
      if (text !== (statesRef.current.get(run.index)?.text ?? run.text)) updateRun(run, { text })
    },
    [updateRun],
  )

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
        else updateAdded(box.id, { text })
        return
      }
      if (text !== box.text) updateAdded(box.id, { text })
    },
    [removeAdded, updateAdded],
  )

  /** Selects a box; a box that was being typed in is left first, so its text is kept. */
  const select = useCallback((next: Selection) => {
    const active = document.activeElement
    if (active instanceof HTMLElement && active.closest('[data-index], [data-added]')) active.blur()
    setSelected(next)
    setEditing(null)
  }, [])

  function patchSelected(patch: { fontFamily?: string | null; fontSize?: number; bold?: boolean; color?: string }) {
    const current = dataRef.current
    if (!selected || !current) return
    if (selected.kind === 'run') {
      updateRun(current.runs[selected.index], patch)
    } else {
      updateAdded(selected.id, patch)
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
      }
    }
    const box = addedRef.current.find((b) => b.id === selected.id)
    return box
      ? { text: box.text, x: box.x, y: box.y, fontFamily: box.fontFamily, fontSize: box.fontSize, bold: box.bold, color: box.color }
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
    const look = describeSelection()
    if (!look) return
    clipboard = { ...look, from: { documentId, pageIndex } }
    pasteCount.current = 0
    setStatus('줄을 복사했습니다 (Ctrl+V로 붙여넣기)')
  }

  function pasteClipboard() {
    if (!clipboard) {
      setStatus('복사한 줄이 없습니다')
      return
    }
    const { from, ...look } = clipboard
    // On the page it came from a paste lands beside the original; on another page it takes the same spot.
    const samePage = from.documentId === documentId && from.pageIndex === pageIndex
    pasteCount.current += 1
    addBoxFrom(look, samePage ? PASTE_OFFSET * pasteCount.current : 0)
  }

  function duplicateSelection() {
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

  const close = useCallback(async () => {
    await flush()
    onClose()
  }, [flush, onClose])

  async function navigate(delta: -1 | 1) {
    await flush()
    onNavigate(delta)
  }

  async function resetPage() {
    if (!window.confirm('이 페이지의 모든 텍스트 수정과 추가한 텍스트를 되돌릴까요? (Ctrl+Z로 다시 살릴 수 있습니다)')) return
    checkpoint(`reset-page:${Date.now()}`)
    window.clearTimeout(saveTimer.current)
    pendingRuns.current.clear()
    addedDirty.current = false
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
      if (pendingRuns.current.size > 0 || addedDirty.current || lastSaveFailed.current) {
        e.preventDefault()
        e.returnValue = ''
      }
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [])

  const fit = data ? fitZoom(containerWidth, data.pageWidth) : 1
  const zoom = zoomMode === 'fit' ? fit : zoomMode
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
    const restyled = s !== undefined && (s.fontFamily !== null || s.fontSize !== null || s.bold !== null || s.color !== null)
    view = {
      kind: 'run',
      label: `줄 ${run.index + 1}`,
      sourceFont: run.sourceFont,
      autoFamily: run.fontFamily,
      family: s?.fontFamily ?? null,
      size: s?.fontSize ?? run.fontSize,
      bold: s?.bold ?? run.bold,
      color: s?.color ?? run.color,
      canReset: s !== undefined,
      look: s === undefined ? null : !restyled && run.glyphReuse ? 'original' : 'substitute',
      mixedChars: s !== undefined && !restyled && run.glyphReuse ? foreignChars(s.text, run.ownChars) : '',
    }
  } else if (selected?.kind === 'added') {
    const box = added.find((b) => b.id === selected.id)
    if (box) {
      view = {
        kind: 'added',
        label: '새 텍스트',
        sourceFont: null,
        autoFamily: DEFAULT_ADDED_FAMILY,
        family: box.fontFamily,
        size: box.fontSize,
        bold: box.bold,
        color: box.color ?? '#000000',
        canReset: false,
        look: null,
        mixedChars: '',
      }
    }
  }

  const editable = data !== null && !data.rotated

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
      if (ctrl && (code === 'KeyZ' || code === 'KeyY')) {
        if (inField || typedInBox) return
        e.preventDefault()
        if (code === 'KeyY' || e.shiftKey) redo()
        else undo()
        return
      }
      // Ctrl+C copies the line, unless text is selected inside the box being typed in: that is a text copy.
      if (ctrl && code === 'KeyC' && selected && !inField && !(box && textSelected)) {
        e.preventDefault()
        copySelection()
        return
      }
      if (ctrl && code === 'KeyV' && !inField && !box) {
        e.preventDefault()
        pasteClipboard()
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
      if (e.key === 'Escape') {
        if (adding) setAdding(false)
        else if (box || inField) el?.blur()
        else if (editing) setEditing(null)
        else if (selected) setSelected(null)
        else void close()
        return
      }
      if (ctrl && code === 'KeyB' && view && !inField) {
        e.preventDefault()
        patchSelected({ bold: !view.bold })
        return
      }
      const bracket = code === 'BracketRight' || e.key === ']' ? 1 : code === 'BracketLeft' || e.key === '[' ? -1 : 0
      if (ctrl && bracket !== 0 && view && !inField) {
        e.preventDefault()
        patchSelected({ fontSize: round(Math.min(500, Math.max(1, view.size + bracket))) })
        return
      }
      if (box || inField) return

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

  return (
    <div className="te-overlay" role="dialog" aria-modal="true" aria-label="텍스트 편집">
      <header className="te-header">
        <button type="button" onClick={close}>
          ← 페이지 목록
        </button>
        <span className="te-title" title={page.sourceFileName}>
          {page.sourceFileName} · {pageIndex + 1}쪽
        </span>
        <span className="te-spacer" />
        <span className="te-status" aria-live="polite">
          {status}
        </span>
        <span className="te-group">
          <button type="button" onClick={undo} disabled={!editable || history.undo === 0} title="실행 취소 (Ctrl+Z)">
            ↶
          </button>
          <button type="button" onClick={redo} disabled={!editable || history.redo === 0} title="다시 실행 (Ctrl+Y)">
            ↷
          </button>
        </span>
        <button
          type="button"
          className={adding ? 'primary' : ''}
          aria-pressed={adding}
          onClick={() => setAdding((a) => !a)}
          disabled={!editable}
          title="누른 뒤 페이지의 원하는 위치를 클릭하세요 (T)"
        >
          ＋ 텍스트 추가
        </button>
        <span className="te-group">
          <button type="button" onClick={() => navigate(-1)} disabled={position <= 0} title="이전 페이지 (Ctrl+←)">
            ◀
          </button>
          <span className="te-sub">
            {position + 1} / {total}
          </span>
          <button type="button" onClick={() => navigate(1)} disabled={position >= total - 1} title="다음 페이지 (Ctrl+→)">
            ▶
          </button>
        </span>
        <details className="te-more">
          <summary title="더 보기">⋯</summary>
          <div className="te-menu" onClick={(e) => e.currentTarget.parentElement?.removeAttribute('open')}>
            <button type="button" onClick={onOpenFind}>
              찾아 바꾸기… (Ctrl+F)
            </button>
            <button type="button" onClick={applyAll} disabled={!editable}>
              지금 수정 반영 (Ctrl+Shift+S)
            </button>
            <button type="button" onClick={resetPage} disabled={!editable}>
              이 페이지 되돌리기
            </button>
          </div>
        </details>
        <label className="te-flatten" title="각 페이지를 그림으로 바꿔 내보냅니다. 수정 전 글자가 파일에서 완전히 사라지지만 검색·복사는 할 수 없습니다.">
          <input type="checkbox" checked={flatten} onChange={(e) => onFlattenChange(e.target.checked)} />
          이미지로 변환
        </label>
        <button type="button" className="primary" onClick={exportAll} disabled={busy} title="PDF로 내보내기 (Ctrl+S)">
          PDF로 내보내기
        </button>
      </header>

      {editable && (
        <StyleBar
          view={view}
          fonts={fonts}
          onFamily={(family) => patchSelected({ fontFamily: family })}
          onSize={(size) => patchSelected({ fontSize: round(Math.min(500, Math.max(1, size))) })}
          onBold={(bold) => patchSelected({ bold })}
          onColor={(color) => patchSelected({ color })}
          onReset={resetSelectedRun}
          onUnify={unifySelectedRun}
          onDelete={() => selected?.kind === 'added' && removeAdded(selected.id)}
          onDuplicate={duplicateSelection}
          zoom={zoom}
          onZoomIn={() => changeZoom(1)}
          onZoomOut={() => changeZoom(-1)}
          onZoomFit={() => setZoomMode('fit')}
        />
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
            원본 PDF에서 이미 회전된 페이지(/Rotate)는 텍스트 편집을 지원하지 않습니다.
          </p>
        )}
        {data && !data.rotated && data.runs.length === 0 && (
          <p className="te-notice">
            이 페이지에는 고칠 수 있는 기존 글자가 없습니다 (빈 페이지이거나 스캔 이미지일 수 있습니다). 위의 ‘＋ 텍스트 추가’로
            글자를 넣을 수 있습니다.
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

      {runStates.size > 0 && (
        <p className="te-redact-note">
          ⚠ 수정한 줄의 원래 글자는 PDF 안에 가려진 채 남아 있어서, 다른 프로그램에서 검색하거나 복사하면 보일 수 있습니다. 완전히
          없애려면 내보낼 때 ‘이미지로 변환’을 켜세요.
        </p>
      )}
      <p className="te-hint">
        클릭하면 선택되고, 한 번 더 클릭하거나 더블클릭·Enter로 글자를 고칩니다. 상자를 끌거나 방향키로 옮기면 다른 줄에 정렬선이
        붙습니다. 위쪽 막대에서 글꼴·크기·굵기·색을 바꿉니다. 수정한 부분은 원래 배경색으로 덮은 뒤 다시 그리는 방식이라 원본 글꼴이
        완벽히 재현되지 않을 수 있습니다.
      </p>
      <p className="te-hint te-keys">
        <b>단축키</b> Ctrl+Z 실행 취소 · Ctrl+Y 다시 실행 · Ctrl+S PDF 내보내기 · Ctrl+Shift+S 수정 반영 · Ctrl+C/V 줄 복사·붙여넣기 ·
        Ctrl+D 복제 · Ctrl+F 찾아 바꾸기 · Ctrl+±/0 확대·축소·폭 맞춤 · Esc 단계별 빠져나오기 · T 텍스트 추가 · Delete 삭제 · 방향키 이동
        (Shift는 10배) · Ctrl+B 굵게 · Ctrl+] / [ 글자 크기 · Ctrl+←/→ 이전/다음 페이지
      </p>
    </div>
  )
}
