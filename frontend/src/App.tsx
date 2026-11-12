import { useCallback, useEffect, useRef, useState } from 'react'
import './App.css'
import {
  ApiError,
  createBlankDocument,
  deleteDocument,
  duplicatePage,
  fetchProblemReport,
  straightenPage,
  exportImages,
  exportPdf,
  fetchAppInfo,
  fetchDocumentInfo,
  fetchSettings,
  fetchUpdate,
  installUpdate,
  recognizePage,
  reloadFonts,
  saveSettings,
  uploadDocument,
} from './api'
import { familyList, reloadMessage } from './fontHelp'
import { printPdf } from './print'
import { redactionMessage } from './redaction'
import { exportFileName } from './exportName'
import { keptPermissions } from './lock'
import { LICENSE_LINKS } from './licenses'
import { pagesToSheets, pagesToWorkbook, pagesToXlsx, workbookName } from './excelExport'
import { SheetPolish } from './components/SheetPolish'
import type { Sheet } from './tableGrid'
import { DEFAULT_IMAGE_CHOICE, imageExportFileName, imageExportStem } from './imageExport'
import type { ImageChoice } from './imageExport'
import { ocrMessage, recognizeAll, straightenedNote } from './ocr'
import { keyCode } from './keys'
import { dragHasFiles, pickPdfs } from './pdfFiles'
import { clearSession, loadSession, saveSession } from './session'
import { loadRecent, orphans, saveRecent, whenText, withWork } from './recent'
import type { RecentWork } from './recent'
import { outcomeNote, readSkippedVersion, shouldAnnounce, skipVersion } from './updateNotice'
import { CompareView } from './components/CompareView'
import { FindReplaceDialog } from './components/FindReplaceDialog'
import { HwpEditor } from './components/HwpEditor'
import { ImageExportDialog } from './components/ImageExportDialog'
import { LockDialog } from './components/LockDialog'
import { PasswordDialog } from './components/PasswordDialog'
import { RetentionDialog } from './components/RetentionDialog'
import { ScanSettingsDialog } from './components/ScanSettingsDialog'
import { BatchDialog } from './components/BatchDialog'
import type { BatchChoice } from './components/BatchDialog'
import { batchZipName, filesOf, outputName } from './batch'
import { zip } from './xlsx'
import { RedactDialog } from './components/RedactDialog'
import { RedactEditor } from './components/RedactEditor'
import { UpdateBanner } from './components/UpdateBanner'
import { PageGrid } from './components/PageGrid'
import { TextEditor } from './components/TextEditor'
import { UploadZone } from './components/UploadZone'
import { RibbonButton, RibbonGroup, RibbonPanel } from './components/Ribbon'
import {
  ArrowLeft,
  ArrowRight,
  CopyPlus,
  Eraser,
  EyeOff,
  FileDown,
  FileImage,
  FileLock,
  FileOutput,
  FilePlus,
  FilePen,
  FolderOpen,
  GitCompareArrows,
  Printer,
  Redo2,
  RotateCcw,
  RotateCw,
  ScanText,
  Search,
  ImageDown,
  FileSpreadsheet,
  Shrink,
  SquareCheck,
  Trash,
  Undo2,
  Files,
  Archive,
  Table2,
} from 'lucide-react'
import type { AppInfo, AppSettings, PageItem, ReplaceResult, UpdateInfo, UploadResponse } from './types'
import { downloadBlob } from './download'
import { errorText } from './errors'
import { usePageList } from './pageHistory'
import { canMoveBy, moveBy } from './pageOrder'
import { shrinkMessage, type ShrinkReport } from './shrink'

/**
 * The name the screen shows. The installed program, its folders and its installer keep the name WINTECH_PDF:
 * updates and existing installations find each other by it.
 */
const APP_NAME = 'WINTECH 문서편집기'

/** An open password question: the upload waits on `resolve` (null: open the file no more). */
interface PasswordAsk {
  fileName: string
  wrong: boolean
  resolve: (password: string | null) => void
}

/** An open question whether to lock an export: the export waits on `resolve`. */
interface LockAsk {
  fromProtected: boolean
  /** The password to lock with, 'plain' for none, null to export nothing. */
  resolve: (choice: { password: string } | 'plain' | null) => void
}

/** How an export is locked: with this password, allowing these permissions (all when undefined). */
type ExportLock = { password: string; permissions?: number }

const rotate = (p: PageItem, delta: 90 | -90): PageItem => ({
  ...p,
  rotation: (((p.rotation + delta) % 360) + 360) % 360 as PageItem['rotation'],
})

function App() {
  // The page list; what the user does to it (add, remove, move, turn) can be undone with Ctrl+Z.
  const { pages, setPages, changePages, undo: undoPages, redo: redoPages, forget: forgetPageHistory, canUndo, canRedo } =
    usePageList()
  const [selectedKeys, setSelectedKeys] = useState<Set<string>>(new Set())
  const [busy, setBusy] = useState(false)
  const [editingKey, setEditingKey] = useState<string | null>(null)
  /** Changes whenever the open editor must start over, e.g. after a replace changed its page behind its back. */
  const [editorNonce, setEditorNonce] = useState(0)
  const [info, setInfo] = useState<AppInfo | null>(null)
  const [settings, setSettings] = useState<AppSettings | null>(null)
  const [scanSettingsOpen, setScanSettingsOpen] = useState(false)
  const [batchOpen, setBatchOpen] = useState(false)
  /** The page the editor is to recognize as soon as it opens on it (a page just set straight for that). */
  const [recognizeOnOpen, setRecognizeOnOpen] = useState<{ key: string; upright: boolean } | null>(null)
  const [retentionOpen, setRetentionOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [passwordAsk, setPasswordAsk] = useState<PasswordAsk | null>(null)
  const [lockAsk, setLockAsk] = useState<LockAsk | null>(null)
  /** The pages "사진으로 내보내기" is asking about (picked: the selection rather than all), and the last choice. */
  const [imageAsk, setImageAsk] = useState<{ pages: PageItem[]; picked: boolean } | null>(null)
  const [imageChoice, setImageChoice] = useState<ImageChoice>(DEFAULT_IMAGE_CHOICE)
  /** Just-opened documents that look scanned: the screen offers to recognize their text. */
  const [scanSuggest, setScanSuggest] = useState<{ files: string[]; pages: PageItem[] } | null>(null)
  /** How far a text recognition run is; null when none runs. */
  /** How far reading the scans got; `straightening` while the pages are first set straight (no text is read yet). */
  const [ocrProgress, setOcrProgress] = useState<{ done: number; total: number; straightening?: boolean } | null>(null)
  const ocrStop = useRef(false)
  /** A note about the last upload or export (a file opened with its password, what a redaction did). */
  const [notice, setNotice] = useState<string | null>(null)
  /** Page lists put aside (보관하고 비우기), to be taken up again from the start screen. */
  const [recent, setRecent] = useState<RecentWork[]>(loadRecent)
  const [redactOpen, setRedactOpen] = useState(false)
  const [compareOpen, setCompareOpen] = useState(false)
  /** 표 다듬기: the sheets of the chosen pages open as a spreadsheet, and the name they are saved under. */
  const [polish, setPolish] = useState<{ sheets: { name: string; sheet: Sheet }[]; fileName: string } | null>(null)
  /** A Hangul document open in the HWP editor, or null. */
  /** The Hangul document open in the HWP editor: a file, or a new empty document (file null); null when none is. */
  const [hwpDoc, setHwpDoc] = useState<{ file: File | null; key: string } | null>(null)
  /** The page the redaction areas are being drawn on, or null. */
  const [drawKey, setDrawKey] = useState<string | null>(null)
  /** Export every page as a picture, so nothing of the original (covered-up) text stays in the file. */
  const [flatten, setFlatten] = useState(false)
  /** "용량 줄이기": exports store their pictures again, smaller (scans of tens of megabytes, for a mail). */
  const [compress, setCompress] = useState(false)
  const [findOpen, setFindOpen] = useState(false)
  /** What find and replace opens looking for (a word picked in the editor), or nothing. */
  const [findQuery, setFindQuery] = useState('')
  /** Whether the pages of the last visit have been looked up yet; nothing is saved before that. */
  const [restored, setRestored] = useState(() => loadSession().length === 0)
  const [restoredNote, setRestoredNote] = useState<string | null>(null)
  const [fontsNote, setFontsNote] = useState<string | null>(null)
  const hadPages = useRef(false)
  /** The newest published version (null until the check answered, or when updating is off). */
  const [update, setUpdate] = useState<UpdateInfo | null>(null)
  const [updateState, setUpdateState] = useState<'idle' | 'installing' | 'launched'>('idle')
  const [skippedVersion, setSkippedVersion] = useState<string | null>(readSkippedVersion)
  const [updateChecking, setUpdateChecking] = useState(false)
  const [updateNote, setUpdateNote] = useState<string | null>(null)

  // Looked up once at start; the server remembers the answer for a few hours, so this costs nothing.
  useEffect(() => {
    fetchUpdate(false)
      .then((loaded) => {
        setUpdate(loaded)
        const said = outcomeNote(loaded)
        if (said) setUpdateNote(said)
      })
      .catch(() => setUpdate(null))
  }, [])

  useEffect(() => {
    fetchAppInfo()
      .then((loaded) => {
        setInfo(loaded)
        document.title = `${APP_NAME} v${loaded.version}`
      })
      .catch(() => setInfo(null))
    fetchSettings()
      .then(setSettings)
      .catch(() => setSettings(null))
  }, [])

  // Pick up the pages of the last visit: the server keeps the documents and their edits.
  useEffect(() => {
    let cancelled = false
    const stored = loadSession()
    if (stored.length === 0) return // nothing to pick up; `restored` already starts out true then
    const ids = [...new Set(stored.map((p) => p.documentId))]
    Promise.all(ids.map((id) => fetchDocumentInfo(id).then((found) => [id, found] as const)))
      .then((results) => {
        if (cancelled) return
        const alive = new Map(results.flatMap(([id, found]) => (found ? [[id, found] as const] : [])))
        const kept = stored.filter((p) => {
          const found = alive.get(p.documentId)
          return found !== undefined && p.pageIndex < found.pageCount
        })
        setPages(kept)
        setRestoredNote(
          kept.length > 0
            ? `이전 작업 ${kept.length}페이지를 불러왔습니다.`
            : '이전 작업의 문서가 서버에 남아 있지 않아 불러오지 못했습니다.',
        )
        setRestored(true)
      })
      .catch(() => {
        // The server could not be asked: start empty, and leave the stored session alone until pages are added.
        if (!cancelled) setRestored(true)
      })
    return () => {
      cancelled = true
    }
  }, [setPages])

  useEffect(() => {
    if (!restored) return
    if (pages.length === 0 && !hadPages.current) return
    hadPages.current = pages.length > 0
    saveSession(pages)
  }, [pages, restored])

  async function handleFiles(files: File[]) {
    const { pdfs, hwps, skipped } = pickPdfs(files)
    const problems: string[] = []
    if (skipped.length > 0) problems.push(`열 수 있는 문서(PDF·한글·Word·Excel·PowerPoint)가 아니라서 건너뛴 파일: ${skipped.join(', ')}`)
    if (hwps.length > 0) {
      setHwpDoc({ file: hwps[0], key: `${hwps[0].name}-${hwps[0].lastModified}` })
      if (hwps.length > 1) {
        problems.push(`한글 문서는 한 번에 하나씩 엽니다. 열지 않은 파일: ${hwps.slice(1).map((f) => f.name).join(', ')}`)
      }
    }
    if (pdfs.length === 0) {
      setError(problems.join('\n') || null)
      return
    }
    setBusy(true)
    setError(null)
    const unlocked: string[] = []
    const converted: string[] = []
    const scanned: { files: string[]; pages: PageItem[] } = { files: [], pages: [] }
    // One bad file must not keep the others from being opened.
    for (const file of pdfs) {
      try {
        const uploaded = await uploadWithPassword(file)
        if (uploaded === null) {
          problems.push(`암호를 입력하지 않아 열지 않은 파일: ${file.name}`)
          continue
        }
        if (uploaded.unlocked) unlocked.push(uploaded.fileName)
        if (uploaded.convertedFrom) converted.push(uploaded.convertedFrom)
        const newPages: PageItem[] = Array.from({ length: uploaded.pageCount }, (_, i) => ({
          key: `${uploaded.documentId}-${i}`,
          documentId: uploaded.documentId,
          sourceFileName: uploaded.fileName,
          pageIndex: i,
          rotation: 0,
          textRevision: 0,
          // -4: every permission bit, for a server that does not report them.
          ...(uploaded.unlocked ? { lockedPermissions: uploaded.permissions ?? -4 } : {}),
        }))
        changePages((prev) => [...prev, ...newPages])
        if (uploaded.scanned) {
          scanned.files.push(uploaded.fileName)
          scanned.pages.push(...newPages)
        }
      } catch (e) {
        problems.push(errorText(e, `업로드 중 오류가 발생했습니다: ${file.name}`))
      }
    }
    setError(problems.length > 0 ? problems.join('\n') : null)
    if (unlocked.length > 0) {
      setNotice(`암호를 풀어서 열었습니다: ${unlocked.join(', ')}. 내보낼 때 암호를 다시 걸 수 있습니다.`)
    } else if (converted.length > 0) {
      setNotice(
        `MS Office로 PDF로 바꿔 열었습니다: ${converted.join(', ')}. 고친 결과는 PDF로 저장됩니다 (원래 파일은 그대로).`,
      )
    }
    setBusy(false)
    if (scanned.pages.length > 0 && settings?.autoOcr === true) {
      // Opened, then read at once (set straight first), as a reader that recognizes scans does; 중지 stops it.
      setScanSuggest(null)
      void handleOcr(scanned.pages)
    } else {
      setScanSuggest(scanned.pages.length > 0 ? scanned : null)
    }
  }

  /**
   * Recognizes the text of scanned pages, one after another: the given ones, else the selected ones, else all.
   * The result is kept by the server and goes into exports as invisible text.
   *
   * @returns what was done, as told to the user; null when nothing ran or it failed (the error is shown)
   */
  async function handleOcr(target?: PageItem[]): Promise<string | null> {
    const chosen = target ?? (selectedKeys.size > 0 ? pages.filter((p) => selectedKeys.has(p.key)) : pages)
    if (chosen.length === 0 || busy) return null
    setBusy(true)
    setError(null)
    setScanSuggest(null)
    try {
      // Pages fed askew are set straight first: they are read better, and their lines then lie level.
      const { copies: turned, upright } =
        settings?.straightenOnOcr === false ? { copies: new Map<string, PageItem>(), upright: 0 } : await straighten(chosen)
      if (ocrStop.current) {
        // Stopped while the pages were being set straight: nothing is read.
        const stopped = (straightenedNote(turned.size, upright) + '글자 인식은 중지했습니다.').trim()
        setNotice(stopped)
        return stopped
      }
      const message =
        straightenedNote(turned.size, upright) + (await recognize(chosen.map((p) => turned.get(p.key) ?? p)))
      setNotice(message)
      return message
    } catch (e) {
      setError(errorText(e, '글자를 인식하지 못했습니다'))
      return null
    } finally {
      setOcrProgress(null)
      setBusy(false)
    }
  }

  /** Recognizes the pages' text, showing how far it got; what it found, said for the user. */
  async function recognize(reading: PageItem[]): Promise<string> {
    ocrStop.current = false
    try {
      const summary = await recognizeAll(
        reading,
        recognizePage,
        (done, total) => setOcrProgress({ done, total }),
        () => ocrStop.current,
      )
      setPages((prev) =>
        prev.map((p) => (p.key in summary.linesByKey ? { ...p, ocrLines: summary.linesByKey[p.key] } : p)),
      )
      return ocrMessage(summary)
    } finally {
      setOcrProgress(null)
    }
  }

  /**
   * The scanned pages fed askew, each replaced in the list by its straightened copy (one step Ctrl+Z takes back);
   * the copies by the key of the page they replace. Pages straight already, or not scans, are left as they are.
   */
  async function straighten(targets: PageItem[]): Promise<{ copies: Map<string, PageItem>; upright: number }> {
    const copies = new Map<string, PageItem>()
    let upright = 0
    ocrStop.current = false
    for (const [done, p] of targets.entries()) {
      // Shown as it goes (a second or so a page), and stopped by 중지 like the reading: the pages set straight so
      // far are kept.
      if (ocrStop.current) break
      setOcrProgress({ done, total: targets.length, straightening: true })
      let result
      try {
        result = await straightenPage(p.documentId, p.pageIndex, p.rotation)
      } catch (e) {
        setOcrProgress(null)
        throw e
      }
      if (!result.documentId) continue
      if (result.quarter !== 0) upright++
      copies.set(p.key, {
        key: `${result.documentId}-0`,
        documentId: result.documentId,
        sourceFileName: p.sourceFileName,
        pageIndex: 0,
        rotation: p.rotation,
        textRevision: 0,
        ...(p.lockedPermissions === undefined ? {} : { lockedPermissions: p.lockedPermissions }),
      })
    }
    // The reading that follows shows its own progress; alone (the editor's one page) nothing is left showing.
    setOcrProgress(null)
    if (copies.size > 0) {
      changePages((prev) => prev.map((p) => copies.get(p.key) ?? p))
      setSelectedKeys((prev) => new Set([...prev].map((k) => copies.get(k)?.key ?? k)))
    }
    return { copies, upright }
  }

  /**
   * The menu's 기울기 바로잡기: the pages set straight; those whose text was recognized are recognized again, as
   * their lines' places were the askew ones.
   */
  async function handleStraighten(keys: string[]) {
    const targets = pages.filter((p) => keys.includes(p.key))
    if (targets.length === 0 || busy) return
    setBusy(true)
    setError(null)
    try {
      const { copies: turned, upright } = await straighten(targets)
      if (turned.size === 0) {
        setNotice('바로잡을 만큼 기울거나 뒤집힌 스캔 쪽이 없습니다 (글자로 된 쪽은 그대로 둡니다).')
        return
      }
      const again = targets.filter((p) => turned.has(p.key) && (p.ocrLines ?? 0) > 0).map((p) => turned.get(p.key)!)
      const note = straightenedNote(turned.size, upright)
      setNotice(again.length > 0 && !ocrStop.current ? note + (await recognize(again)) : note.trim())
    } catch (e) {
      setError(errorText(e, '쪽의 기울기를 바로잡지 못했습니다'))
    } finally {
      setOcrProgress(null)
      setBusy(false)
    }
  }

  /**
   * 파일마다 처리: for each chosen file, its scanned pages recognized (set straight first) if asked, then saved on its
   * own - a PDF (smaller if asked) and/or a workbook. One result is downloaded as it is; several come in one ZIP.
   */
  async function handleBatch(choice: BatchChoice) {
    setBatchOpen(false)
    if (busy) return
    const groups = filesOf(pages).filter((f) => choice.files.includes(f.name))
    if (groups.length === 0) return
    setBusy(true)
    setError(null)
    const results: [string, Uint8Array][] = []
    const taken = new Set<string>()
    let recognizedLines = 0
    let turned = 0
    try {
      for (const [i, group] of groups.entries()) {
        let filePages = group.pages
        if (choice.ocr) {
          setNotice(`파일마다 처리 중… ${i + 1}/${groups.length} ${group.name}: 글자 인식`)
          const { copies } =
            settings?.straightenOnOcr === false ? { copies: new Map<string, PageItem>() } : await straighten(filePages)
          turned += copies.size
          filePages = filePages.map((p) => copies.get(p.key) ?? p)
          const summary = await recognizeAll(filePages, recognizePage, (done, total) => setOcrProgress({ done, total }), () => ocrStop.current)
          setOcrProgress(null)
          recognizedLines += summary.lines
          setPages((prev) =>
            prev.map((p) => (p.key in summary.linesByKey ? { ...p, ocrLines: summary.linesByKey[p.key] } : p)),
          )
          if (summary.stopped) break
        }
        setNotice(`파일마다 처리 중… ${i + 1}/${groups.length} ${group.name}: 저장`)
        if (choice.pdf) {
          const name = outputName(group.name, 'pdf', taken)
          const { blob } = await exportPdf(filePages, name, flatten, undefined, choice.shrink)
          results.push([name, new Uint8Array(await blob.arrayBuffer())])
        }
        if (choice.excel) {
          const name = outputName(group.name, 'xlsx', taken)
          results.push([name, new Uint8Array(await (await pagesToXlsx(filePages, filePages)).arrayBuffer())])
        }
      }
      if (results.length === 1) {
        downloadBlob(new Blob([results[0][1] as Uint8Array<ArrayBuffer>]), results[0][0])
      } else if (results.length > 1) {
        downloadBlob(new Blob([zip(results)], { type: 'application/zip' }), batchZipName(new Date()))
      }
      const did = [
        `파일 ${groups.length}개를 처리해 ${results.length}개로 저장했습니다`,
        choice.ocr ? `글자 ${recognizedLines}줄 인식` : '',
        turned > 0 ? `${turned}쪽 방향·기울기 바로잡음` : '',
      ].filter(Boolean)
      setNotice(`${did.join(' · ')}.`)
    } catch (e) {
      setNotice(null)
      setError(errorText(e, '파일마다 처리하지 못했습니다'))
    } finally {
      setOcrProgress(null)
      setBusy(false)
    }
  }

  /** 문제 보고: the logs and a page about this PC, saved as a ZIP for the user to send to whoever looks into it. */
  async function handleProblemReport() {
    try {
      const { blob, fileName } = await fetchProblemReport()
      downloadBlob(blob, fileName)
      setNotice(
        `문제 보고 파일을 내려받았습니다: 다운로드 폴더의 ${fileName}을(를) 담당자에게 보내 주세요. ` +
          '프로그램 기록과 PC 정보만 들어 있고, 문서 내용은 들어 있지 않습니다.',
      )
    } catch (e) {
      setError(errorText(e, '문제 보고 파일을 만들지 못했습니다'))
    }
  }

  /** Uploads the file, asking for its password for as long as the server refuses one; null when the user gives up. */
  async function uploadWithPassword(file: File): Promise<UploadResponse | null> {
    let password: string | undefined
    for (;;) {
      try {
        return await uploadDocument(file, password)
      } catch (e) {
        if (!(e instanceof ApiError && e.status === 401)) throw e
        const wrong = password !== undefined
        const given = await new Promise<string | null>((resolve) => setPasswordAsk({ fileName: file.name, wrong, resolve }))
        setPasswordAsk(null)
        if (given === null) return null
        password = given
      }
    }
  }

  /**
   * A PDF dropped anywhere on the window is opened like one dropped on the upload box; without this the
   * browser would navigate away to show the file and the work in progress would look lost.
   */
  const filesDropped = useRef<(files: File[]) => void>(() => {})
  useEffect(() => {
    filesDropped.current = (files) => {
      if (editingKey === null && hwpDoc === null && !busy) void handleFiles(files)
    }
  })
  useEffect(() => {
    const over = (e: DragEvent) => {
      if (dragHasFiles(e.dataTransfer?.types)) e.preventDefault()
    }
    const drop = (e: DragEvent) => {
      if (e.defaultPrevented || !dragHasFiles(e.dataTransfer?.types)) return
      e.preventDefault()
      filesDropped.current(Array.from(e.dataTransfer?.files ?? []))
    }
    window.addEventListener('dragover', over)
    window.addEventListener('drop', drop)
    return () => {
      window.removeEventListener('dragover', over)
      window.removeEventListener('drop', drop)
    }
  }, [])

  function handleRotate(key: string, delta: 90 | -90) {
    changePages((prev) => prev.map((p) => (p.key === key ? rotate(p, delta) : p)))
  }

  function handleDelete(key: string) {
    changePages((prev) => prev.filter((p) => p.key !== key))
    setSelectedKeys((prev) => {
      if (!prev.has(key)) return prev
      const next = new Set(prev)
      next.delete(key)
      return next
    })
  }

  function handleToggleSelect(key: string) {
    setSelectedKeys((prev) => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }

  function clearSelection() {
    setSelectedKeys(new Set())
  }

  async function handleInsertBlank(afterKey?: string) {
    setBusy(true)
    setError(null)
    try {
      const blank = await createBlankDocument()
      const newPage: PageItem = {
        key: `${blank.documentId}-0`,
        documentId: blank.documentId,
        sourceFileName: blank.fileName,
        pageIndex: 0,
        rotation: 0,
        textRevision: 0,
      }
      changePages((prev) => {
        if (!afterKey) return [...prev, newPage]
        const index = prev.findIndex((p) => p.key === afterKey)
        if (index === -1) return [...prev, newPage]
        const next = [...prev]
        next.splice(index + 1, 0, newPage)
        return next
      })
    } catch (e) {
      setError(errorText(e, '빈 페이지 추가 중 오류가 발생했습니다'))
    } finally {
      setBusy(false)
    }
  }

  /** The selected pages one place earlier or later (Ctrl+← / Ctrl+→, the ribbon): one step Ctrl+Z takes back. */
  function handleMoveSelected(delta: -1 | 1) {
    if (busy || selectedKeys.size === 0) return
    changePages((prev) => moveBy(prev, selectedKeys, delta))
  }

  function handleBulkRotate(delta: 90 | -90) {
    changePages((prev) => prev.map((p) => (selectedKeys.has(p.key) ? rotate(p, delta) : p)))
  }

  /** Copies the pages, edits and all, each right after its original; afterwards each is edited on its own. */
  async function handleDuplicate(keys: string[]) {
    const targets = pages.filter((p) => keys.includes(p.key))
    if (targets.length === 0 || busy) return
    setBusy(true)
    setError(null)
    try {
      const copies = new Map<string, PageItem>()
      for (const p of targets) {
        const copy = await duplicatePage(p.documentId, p.pageIndex)
        copies.set(p.key, {
          key: `${copy.documentId}-0`,
          documentId: copy.documentId,
          sourceFileName: p.sourceFileName,
          pageIndex: 0,
          rotation: p.rotation,
          textRevision: 0,
          ...(p.lockedPermissions === undefined ? {} : { lockedPermissions: p.lockedPermissions }),
          ...(p.ocrLines === undefined ? {} : { ocrLines: p.ocrLines }),
        })
      }
      changePages((prev) => prev.flatMap((p) => (copies.has(p.key) ? [p, copies.get(p.key)!] : [p])))
    } catch (e) {
      setError(errorText(e, '페이지를 복제하지 못했습니다'))
    } finally {
      setBusy(false)
    }
  }

  function handleBulkDelete() {
    changePages((prev) => prev.filter((p) => !selectedKeys.has(p.key)))
    clearSelection()
  }

  /**
   * Whether an export of these pages is locked: asked when they come from a protected document, or always
   * (the menu's "암호 걸어 내보내기"). Undefined for no password, null when the user gave up.
   */
  async function askLock(target: PageItem[], always = false): Promise<ExportLock | undefined | null> {
    const permissions = keptPermissions(target)
    if (!always && permissions === undefined) return undefined
    const choice = await new Promise<{ password: string } | 'plain' | null>((resolve) =>
      setLockAsk({ fromProtected: permissions !== undefined, resolve }),
    )
    setLockAsk(null)
    if (choice === null) return null
    if (choice === 'plain') return undefined
    return { password: choice.password, permissions }
  }

  function exportedNotice(
    redaction: Parameters<typeof redactionMessage>[0],
    lock: ExportLock | undefined,
    shrink: ShrinkReport | null,
    fileSize: number,
  ) {
    const lines = [
      lock ? '암호를 걸어 내보냈습니다.' : null,
      redactionMessage(redaction),
      compress ? shrinkMessage(shrink, fileSize, flatten) : null,
    ].filter(Boolean)
    return lines.length > 0 ? lines.join(' ') : null
  }

  async function handleExtract(targetPages: PageItem[], fileName: string) {
    if (targetPages.length === 0) return
    const lock = await askLock(targetPages)
    if (lock === null) return
    setBusy(true)
    setError(null)
    try {
      const { blob, redaction, shrink } = await exportPdf(targetPages, fileName, flatten, lock, compress)
      downloadBlob(blob, fileName)
      setNotice(exportedNotice(redaction, lock, shrink, blob.size))
    } catch (e) {
      setError(errorText(e, '추출 중 오류가 발생했습니다'))
    } finally {
      setBusy(false)
    }
  }

  function handleExtractOne(key: string) {
    const page = pages.find((p) => p.key === key)
    if (!page) return
    handleExtract([page], `page-${pages.indexOf(page) + 1}.pdf`)
  }

  function handleExtractSelected() {
    const targetPages = pages.filter((p) => selectedKeys.has(p.key))
    handleExtract(targetPages, `extracted-${targetPages.length}pages.pdf`)
  }

  /** Downloads all pages as one PDF, named after the source file ("계약서_수정본.pdf"). */
  /**
   * Prints the pages as they would be exported (edits, redaction and "이미지로 변환" included): the
   * selected ones when some are selected, else all.
   */
  async function handlePrint(target?: PageItem[]) {
    const chosen = target ?? (selectedKeys.size > 0 ? pages.filter((p) => selectedKeys.has(p.key)) : pages)
    if (chosen.length === 0 || busy) return
    setBusy(true)
    setError(null)
    try {
      const { blob } = await exportPdf(chosen, exportFileName(chosen), flatten)
      await printPdf(blob)
    } catch (e) {
      setError(errorText(e, '인쇄할 문서를 만들지 못했습니다'))
    } finally {
      setBusy(false)
    }
  }

  /** 사진으로 내보내기: asks how, for the given pages, else the selected ones, else all. */
  function askImageExport(target?: PageItem[]) {
    if (busy) return
    const picked = target === undefined && selectedKeys.size > 0
    const chosen = target ?? (picked ? pages.filter((p) => selectedKeys.has(p.key)) : pages)
    if (chosen.length > 0) setImageAsk({ pages: chosen, picked })
  }

  async function handleImageExport(target: PageItem[], choice: ImageChoice) {
    setImageAsk(null)
    setImageChoice(choice)
    setBusy(true)
    setError(null)
    try {
      const stem = imageExportStem(target, pages)
      const { blob, redaction } = await exportImages(target, stem, choice.format, choice.dpi)
      downloadBlob(blob, imageExportFileName(stem, target.length, choice.format))
      const saved = target.length === 1 ? '사진 1장으로 저장했습니다.' : `사진 ${target.length}장을 ZIP 파일로 저장했습니다.`
      setNotice([saved, redactionMessage(redaction)].filter(Boolean).join(' '))
    } catch (e) {
      setError(errorText(e, '사진으로 내보내지 못했습니다'))
    } finally {
      setBusy(false)
    }
  }

  /**
   * Saves the pages (given, else the selected ones, else all) as an Excel workbook, a sheet for each page laid out as
   * the page is: its tables with their column widths, merged cells and borders.
   */
  async function handleExcel(target?: PageItem[]) {
    if (busy) return
    const chosen = target ?? (selectedKeys.size > 0 ? pages.filter((p) => selectedKeys.has(p.key)) : pages)
    if (chosen.length === 0) return
    setBusy(true)
    setError(null)
    try {
      const sources = new Set(chosen.map((p) => p.sourceFileName))
      const base = sources.size === 1 ? [...sources][0] : '합친 문서'
      const one = chosen.length === 1 && pages.length > 1 ? pages.findIndex((p) => p.key === chosen[0].key) + 1 : undefined
      const name = workbookName(base, one)
      const { blob, joined } = await pagesToWorkbook(chosen, pages)
      downloadBlob(blob, name)
      setNotice(
        `${name}로 저장했습니다 (${chosen.length === 1 ? '1쪽' : `${chosen.length}쪽, 쪽마다 시트 한 장`}` +
          (joined > 0 ? `, 여러 쪽에 걸친 표 ${joined}개는 맨 앞 '이어진 표' 시트에 한데 모음` : '') +
          ').',
      )
    } catch (e) {
      setError(errorText(e, '엑셀 파일로 저장하지 못했습니다'))
    } finally {
      setBusy(false)
    }
  }

  /**
   * 표 다듬기: the same sheets "엑셀로 내보내기" would write, opened as a spreadsheet to be put right first.
   */
  async function handlePolish() {
    if (busy) return
    const chosen = selectedKeys.size > 0 ? pages.filter((p) => selectedKeys.has(p.key)) : pages
    if (chosen.length === 0) return
    setBusy(true)
    setError(null)
    try {
      const sources = new Set(chosen.map((p) => p.sourceFileName))
      const base = sources.size === 1 ? [...sources][0] : '합친 문서'
      const one = chosen.length === 1 && pages.length > 1 ? pages.findIndex((p) => p.key === chosen[0].key) + 1 : undefined
      const { sheets } = await pagesToSheets(chosen, pages)
      if (sheets.every((s) => s.sheet.cells.length === 0)) {
        setNotice('표로 옮길 글자가 없습니다. 스캔 문서는 먼저 글자 인식을 해 주세요.')
        return
      }
      setPolish({ sheets, fileName: workbookName(base, one) })
    } catch (e) {
      setError(errorText(e, '표를 열지 못했습니다'))
    } finally {
      setBusy(false)
    }
  }

  /** @param locked from "암호 걸어 내보내기": ask for a password even when no page came protected */
  async function handleExport(locked = false) {
    if (pages.length === 0 || busy) return
    const lock = await askLock(pages, locked)
    if (lock === null) return
    setBusy(true)
    setError(null)
    try {
      const fileName = exportFileName(pages)
      const { blob, redaction, shrink } = await exportPdf(pages, fileName, flatten, lock, compress)
      downloadBlob(blob, fileName)
      setNotice(exportedNotice(redaction, lock, shrink, blob.size))
    } catch (e) {
      setError(errorText(e, '내보내기 중 오류가 발생했습니다'))
    } finally {
      setBusy(false)
    }
  }

  // The scan offer counts only its pages still in the list: an upload taken back (Ctrl+Z) takes the offer with it.
  const listedKeys = new Set(pages.map((p) => p.key))
  const scanPagesListed = scanSuggest?.pages.filter((p) => listedKeys.has(p.key)) ?? []
  const scanStillListed =
    scanPagesListed.length > 0
      ? { files: [...new Set(scanPagesListed.map((p) => p.sourceFileName))], pages: scanPagesListed }
      : null

  const editingIndex = editingKey === null ? -1 : pages.findIndex((p) => p.key === editingKey)
  const editingPage = editingIndex >= 0 ? pages[editingIndex] : null

  const handleTextEdited = useCallback((key: string) => {
    setPages((prev) => prev.map((p) => (p.key === key ? { ...p, textRevision: p.textRevision + 1 } : p)))
  }, [setPages])

  const handleCloseEditor = useCallback(() => setEditingKey(null), [])

  /** Redaction areas changed on these pages: their thumbnails show the black boxes again. */
  const handleRedactionsChanged = useCallback((changed: { documentId: string; pageIndex: number }[]) => {
    const keys = new Set(changed.map((c) => `${c.documentId}:${c.pageIndex}`))
    setPages((prev) =>
      prev.map((p) => (keys.has(`${p.documentId}:${p.pageIndex}`) ? { ...p, textRevision: p.textRevision + 1 } : p)),
    )
  }, [setPages])

  function handleNavigateEditor(delta: -1 | 1) {
    const next = pages[editingIndex + delta]
    if (next) setEditingKey(next.key)
  }

  /** A replace changed pages on the server: refresh their thumbnails and restart an editor that shows one of them. */
  function handleReplaced(result: ReplaceResult) {
    setPages((prev) =>
      prev.map((p) =>
        result.pages.some((r) => r.documentId === p.documentId && r.pageIndex === p.pageIndex)
          ? { ...p, textRevision: p.textRevision + 1 }
          : p,
      ),
    )
    setEditorNonce((n) => n + 1)
  }

  const [fontsBusy, setFontsBusy] = useState(false)

  /** The installed fonts were read again: the count changes, and every picture is drawn again in case a font arrived. */
  function handleFontsReloaded(fonts: AppInfo) {
    setInfo((prev) =>
      prev ? { ...prev, installedFonts: fonts.installedFonts, unreadableFonts: fonts.unreadableFonts ?? [] } : prev,
    )
    setPages((prev) => prev.map((p) => ({ ...p, textRevision: p.textRevision + 1 })))
  }

  async function handleReloadFontsFromList() {
    if (fontsBusy) return
    setFontsBusy(true)
    try {
      const before = info?.installedFonts
      const next = await reloadFonts()
      handleFontsReloaded(next)
      setFontsNote(reloadMessage(before, next.installedFonts, next.unreadableFonts ?? []))
    } catch (e) {
      setError(errorText(e, '글꼴을 다시 읽지 못했습니다'))
    } finally {
      setFontsBusy(false)
    }
  }

  /** The "업데이트 확인" link: asks GitHub again, and says so when this is already the newest version. */
  async function handleCheckUpdate() {
    if (updateChecking) return
    setUpdateChecking(true)
    setUpdateNote(null)
    try {
      const result = await fetchUpdate(true)
      setUpdate(result)
      if (result.newer) {
        skipVersion('') // asked for it: show the notice even for a version skipped before
        setSkippedVersion(null)
      } else if (result.error) {
        setUpdateNote(result.error)
      } else {
        setUpdateNote(`최신 버전입니다 (v${result.current}).`)
      }
    } catch (e) {
      setUpdateNote(errorText(e, '업데이트를 확인하지 못했습니다'))
    } finally {
      setUpdateChecking(false)
    }
  }

  async function handleInstallUpdate() {
    if (!update || updateState !== 'idle') return
    const ok = window.confirm(
      `v${update.latest} 업데이트를 내려받아 설치합니다.\n\n설치 프로그램이 열리고 이 앱은 종료됩니다. 작업 중인 문서는 저장되어 있어 다시 열면 이어집니다. Windows의 권한 확인 창이 뜨면 '예'를 눌러 주세요.\n\n계속할까요?`,
    )
    if (!ok) return
    setUpdateState('installing')
    try {
      await installUpdate()
      setUpdateState('launched')
    } catch (e) {
      setUpdateState('idle')
      setError(errorText(e, '업데이트하지 못했습니다'))
    }
  }

  function handleSkipUpdate() {
    if (!update?.latest) return
    skipVersion(update.latest)
    setSkippedVersion(update.latest)
  }

  /** Takes back the last change of the page list (Ctrl+Z); the selection is dropped, its pages may be gone. */
  function handleUndo() {
    if (busy || !canUndo) return
    undoPages()
    clearSelection()
  }

  /** Does the last undone change again (Ctrl+Y, Ctrl+Shift+Z). */
  function handleRedo() {
    if (busy || !canRedo) return
    redoPages()
    clearSelection()
  }

  function handleStartOver() {
    if (!window.confirm('모든 페이지와 수정 내용이 삭제되며 되돌릴 수 없습니다. 저장된 문서 사본도 함께 삭제됩니다. 모두 지울까요?')) return
    // The server keeps a copy of every uploaded file; starting over is the moment to remove them - but not one
    // that work put aside still uses.
    const aside = new Set(recent.flatMap((w) => w.pages).map((p) => p.documentId))
    for (const documentId of new Set(pages.map((p) => p.documentId))) {
      if (!aside.has(documentId)) void deleteDocument(documentId)
    }
    setPages([])
    forgetPageHistory() // the documents are deleted from the server too: there is nothing to go back to
    setSelectedKeys(new Set())
    setEditingKey(null)
    setRestoredNote(null)
    clearSession()
  }

  function keepRecent(list: RecentWork[]) {
    setRecent(list)
    saveRecent(list)
  }

  /**
   * 보관하고 비우기: the page list is put aside - its documents and edits stay on the server - and the list is
   * emptied for other work. It is taken up again from 최근 작업 on the start screen.
   */
  function handlePutAside() {
    if (busy || pages.length === 0) return
    const { list, dropped } = withWork(recent, pages, Date.now())
    // The oldest work gives way: its documents go with it, unless something else still uses them.
    for (const gone of dropped) for (const id of orphans(gone, list, [])) void deleteDocument(id)
    keepRecent(list)
    setPages([])
    forgetPageHistory() // undoing would bring back pages that are now put aside as well
    setSelectedKeys(new Set())
    setEditingKey(null)
    setRestoredNote(null)
    clearSession()
    setNotice(`작업을 보관했습니다: ${list[0].title} (${list[0].pages.length}쪽). '최근 작업'에서 다시 열 수 있습니다.`)
  }

  /** Takes work put aside up again: its pages go to the end of the page list, as they were. */
  async function handleOpenRecent(work: RecentWork) {
    if (busy) return
    const rest = recent.filter((w) => w.id !== work.id)
    try {
      const ids = [...new Set(work.pages.map((p) => p.documentId))]
      const found = new Map(await Promise.all(ids.map(async (id) => [id, await fetchDocumentInfo(id)] as const)))
      const have = new Set(pages.map((p) => p.key))
      const kept = work.pages.filter((p) => {
        const info = found.get(p.documentId)
        return info != null && p.pageIndex < info.pageCount && !have.has(p.key)
      })
      keepRecent(rest)
      if (kept.length === 0) {
        setNotice(`'${work.title}'의 문서가 남아 있지 않아 열지 못했습니다 (보관 기간이 지났거나 지워졌습니다).`)
        return
      }
      changePages((prev) => [...prev, ...kept])
      const lost = work.pages.length - kept.length
      setNotice(`'${work.title}' ${kept.length}쪽을 다시 열었습니다.${lost > 0 ? ` ${lost}쪽은 문서가 남아 있지 않아 빠졌습니다.` : ''}`)
    } catch (e) {
      setError(errorText(e, '보관한 작업을 열지 못했습니다. 잠시 뒤 다시 시도해 주세요.'))
    }
  }

  /** Takes work put aside off the list, and its documents off the server. */
  function handleForgetRecent(work: RecentWork) {
    if (!window.confirm(`'${work.title}' 작업과 수정 내용을 지웁니다. 되돌릴 수 없습니다. 지울까요?`)) return
    const rest = recent.filter((w) => w.id !== work.id)
    for (const id of orphans(work, rest, pages)) void deleteDocument(id)
    keepRecent(rest)
  }

  /**
   * Keyboard shortcuts of the page list (the editor has its own). Called through a ref so it always
   * sees the current pages and selection.
   */
  const shortcut = useRef<(e: KeyboardEvent) => void>(() => {})
  useEffect(() => {
    shortcut.current = (e) => {
      if (e.isComposing || e.keyCode === 229 || passwordAsk !== null || lockAsk !== null || imageAsk !== null) return
      const el = e.target instanceof HTMLElement ? e.target : null
      // A page's tick box is not somewhere to type: Ctrl+D or Delete right after ticking one acts on the selection.
      const inField =
        el?.closest(
          'input:not([type=checkbox]), select, textarea, [contenteditable="true"], [contenteditable="plaintext-only"]',
        ) != null
      const ctrl = e.ctrlKey || e.metaKey
      const code = keyCode(e)
      const elsewhere =
        findOpen || redactOpen || compareOpen || polish !== null || hwpDoc !== null || drawKey !== null || editingKey !== null || inField
      // Undo and redo also when every page was taken out of the list: that is when they are wanted most.
      if (!elsewhere && ctrl && !e.altKey && ((code === 'KeyZ' && e.shiftKey) || (code === 'KeyY' && !e.shiftKey))) {
        e.preventDefault()
        handleRedo()
        return
      }
      if (!elsewhere && ctrl && !e.altKey && !e.shiftKey && code === 'KeyZ') {
        e.preventDefault()
        handleUndo()
        return
      }
      if (pages.length === 0) return
      if (ctrl && (code === 'KeyF' || code === 'KeyH')) {
        e.preventDefault()
        setFindOpen(true)
        return
      }
      if (elsewhere) return
      if (ctrl && code === 'KeyS') {
        e.preventDefault()
        void handleExport()
      } else if (ctrl && code === 'KeyP') {
        // The document, not this screen, is what gets printed.
        e.preventDefault()
        void handlePrint()
      } else if (ctrl && code === 'KeyA') {
        e.preventDefault()
        setSelectedKeys(new Set(pages.map((p) => p.key)))
      } else if (ctrl && code === 'KeyD' && selectedKeys.size > 0) {
        // The browser would bookmark the page.
        e.preventDefault()
        void handleDuplicate([...selectedKeys])
      } else if (e.key === 'Delete' && selectedKeys.size > 0) {
        e.preventDefault()
        handleBulkDelete()
      } else if (ctrl && !e.shiftKey && !e.altKey && (e.key === 'ArrowLeft' || e.key === 'ArrowRight') && selectedKeys.size > 0) {
        e.preventDefault()
        handleMoveSelected(e.key === 'ArrowLeft' ? -1 : 1)
      } else if (!ctrl && !e.altKey && code === 'KeyR' && selectedKeys.size > 0) {
        e.preventDefault()
        handleBulkRotate(e.shiftKey ? -90 : 90)
      } else if (e.key === 'Escape' && selectedKeys.size > 0) {
        clearSelection()
      }
    }
  })
  useEffect(() => {
    const listener = (e: KeyboardEvent) => shortcut.current(e)
    window.addEventListener('keydown', listener)
    return () => window.removeEventListener('keydown', listener)
  }, [])

  return (
    <div className="app">
      <header className="app-header">
        <h1>{APP_NAME}</h1>
        <p className="subtitle">PDF · 한글(HWP/HWPX) 편집 · Office 문서 열기 · 페이지 정리와 합치기 · 문서 비교 · 개인정보 가리기</p>
        <p className="app-meta">
          {info
            ? `v${info.version} · 로컬 글꼴 ${info.installedFonts.toLocaleString('ko-KR')}개 읽음`
            : '글꼴 확인 중…'}{' '}
          {info && (info.unreadableFonts?.length ?? 0) > 0 && (
            <span
              className="app-warn"
              title={`이 PC에 .otf 형식으로만 설치된 글꼴이라 이 앱이 읽지 못합니다: ${info.unreadableFonts.join(', ')}. 같은 글꼴의 .ttf 버전을 설치하면 쓸 수 있습니다.`}
            >
              · ⚠ .otf 글꼴 {info.unreadableFonts.length}개는 읽지 못함 ({familyList(info.unreadableFonts, 2)})
            </span>
          )}{' '}
          {info && (
            <button
              type="button"
              className="link-button"
              onClick={() => void handleReloadFontsFromList()}
              disabled={fontsBusy}
              title="글꼴을 새로 설치했다면 눌러서 설치된 글꼴을 다시 읽습니다 (앱을 다시 켤 필요 없음)"
            >
              {fontsBusy ? '읽는 중…' : '⟳ 글꼴 다시 읽기'}
            </button>
          )}
          {settings && (
            <>
              {' · '}
              <button
                type="button"
                className="link-button"
                onClick={() => setRetentionOpen(true)}
                title={`올린 문서와 고친 내용은 ${settings.retentionDays}일 동안 열지 않으면 정리됩니다 (원본 파일은 그대로). 눌러서 기간을 바꿉니다`}
              >
                작업 보관 {settings.retentionDays}일
              </button>
              {' · '}
              <button
                type="button"
                className="link-button"
                onClick={() => setScanSettingsOpen(true)}
                title="스캔 문서를 열면 바로 인식할지, 인식할 때 방향·기울기를 바로잡을지 정합니다"
              >
                글자 인식 설정
              </button>
              {' · '}
              <button
                type="button"
                className="link-button"
                onClick={() => void handleProblemReport()}
                title="프로그램이 이상할 때: 기록 파일과 PC 정보를 ZIP 하나로 내려받아 담당자에게 보낼 수 있습니다 (문서 내용은 들어 있지 않음)"
              >
                문제 보고
              </button>
            </>
          )}
          {update?.enabled && (
            <>
              {' '}
              <button
                type="button"
                className="link-button"
                onClick={() => void handleCheckUpdate()}
                disabled={updateChecking}
                title="새 버전이 있는지 GitHub에서 확인합니다"
              >
                {updateChecking ? '확인 중…' : '업데이트 확인'}
              </button>
            </>
          )}
          {' · '}
          <a
            className="link-button"
            href={LICENSE_LINKS[0].href}
            target="_blank"
            rel="noreferrer"
            title="이 프로그램에 들어 있는 오픈소스의 목록과 라이선스 원문을 새 탭에서 엽니다"
          >
            오픈소스 라이선스
          </a>
        </p>
      </header>

      <UploadZone onFiles={handleFiles} busy={busy} />
      <div className="start-actions">
        <button
          type="button"
          onClick={() => setHwpDoc({ file: null, key: `new-${Date.now()}` })}
          disabled={busy}
          title="빈 한글 문서를 만들어 한글 편집 화면에서 엽니다. HWPX·HWP로 저장합니다 (PC 밖으로 나가지 않음)."
        >
          <FilePen size={16} aria-hidden="true" /> 새 한글 문서
        </button>
      </div>

      {recent.length > 0 && (
        <section className="recent-work" aria-label="최근 작업">
          <h2>최근 작업</h2>
          <ul>
            {recent.map((work) => (
              <li key={work.id}>
                <button type="button" className="recent-open" onClick={() => void handleOpenRecent(work)} disabled={busy} title="보관한 그대로 목록 끝에 다시 엽니다">
                  <span className="recent-title">{work.title}</span>
                  <span className="recent-meta">
                    {work.pages.length}쪽 · {whenText(work.savedAt, Date.now())}
                  </span>
                </button>
                <button type="button" className="recent-forget" onClick={() => handleForgetRecent(work)} disabled={busy} aria-label={`${work.title} 지우기`} title="이 작업과 수정 내용을 지웁니다">
                  <Trash size={14} aria-hidden="true" />
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}

      {restoredNote && (
        <div className="info-banner">
          <span>{restoredNote}</span>
          <button type="button" onClick={() => setRestoredNote(null)} aria-label="닫기">
            ✕
          </button>
        </div>
      )}

      {update && (updateState === 'launched' || shouldAnnounce(update, skippedVersion)) && (
        <UpdateBanner info={update} state={updateState} onInstall={handleInstallUpdate} onSkip={handleSkipUpdate} />
      )}

      {updateNote && (
        <div className="info-banner">
          <span>{updateNote}</span>
          <button type="button" onClick={() => setUpdateNote(null)} aria-label="닫기">
            ✕
          </button>
        </div>
      )}

      {fontsNote && (
        <div className="info-banner">
          <span>{fontsNote}</span>
          <button type="button" onClick={() => setFontsNote(null)} aria-label="닫기">
            ✕
          </button>
        </div>
      )}

      {scanStillListed && !ocrProgress && (
        <div className="info-banner update-banner" role="status">
          <span>
            스캔한 문서로 보입니다: {scanStillListed.files.join(', ')}. 글자 인식(OCR)을 하면 내보낸 PDF에서 글자를
            검색·복사할 수 있습니다 (쪽당 2초쯤).
          </span>
          <button type="button" className="primary" onClick={() => void handleOcr(scanStillListed.pages)} disabled={busy}>
            글자 인식
          </button>
          <button type="button" onClick={() => setScanSuggest(null)}>
            나중에
          </button>
        </div>
      )}

      {ocrProgress && (
        <div className="info-banner update-banner" role="status">
          <span>
            {ocrProgress.straightening ? '스캔 방향·기울기 확인 중…' : '글자 인식 중…'} {ocrProgress.done}/{ocrProgress.total}쪽
          </span>
          <button type="button" onClick={() => (ocrStop.current = true)}>
            중지
          </button>
        </div>
      )}

      {notice && (
        <div className="info-banner" role="status">
          <span>{notice}</span>
          <button type="button" onClick={() => setNotice(null)} aria-label="닫기">
            ✕
          </button>
        </div>
      )}

      {error && (
        <div className="error-banner">
          <span>{error}</span>
          <button type="button" onClick={() => setError(null)} aria-label="닫기">
            ✕
          </button>
        </div>
      )}

      {pages.length === 0 && canUndo && (
        <div className="info-banner" role="status">
          <span>목록에서 페이지를 모두 뺐습니다.</span>
          <button type="button" onClick={handleUndo} disabled={busy}>
            되돌리기 (Ctrl+Z)
          </button>
        </div>
      )}

      {pages.length > 0 && (
        <>
          <div className="page-ribbon">
            <div className="page-ribbon-head">
              <b>{pages.length}페이지</b>
              {selectedKeys.size > 0 && (
                <>
                  <span>· {selectedKeys.size}개 선택됨</span>
                  <button type="button" className="link-button" onClick={clearSelection} disabled={busy} title="선택 해제 (Esc)">
                    선택 해제
                  </button>
                </>
              )}
            </div>
            <RibbonPanel label="페이지 목록 메뉴">
              <RibbonGroup label="파일">
                <RibbonButton
                  icon={<FolderOpen />}
                  label="파일 열기"
                  title="PDF는 목록 끝에 붙이고, 한글 문서(HWP·HWPX)는 한글 편집 화면에서 엽니다 (화면에 끌어다 놓아도 됩니다)"
                  // The upload box above holds the file picker; this opens the same one.
                  onClick={() => document.querySelector<HTMLInputElement>('.upload-zone input[type=file]')?.click()}
                  disabled={busy}
                />
                <RibbonButton icon={<FilePlus />} label="빈 페이지" title="목록 끝에 빈 페이지를 넣습니다" onClick={() => handleInsertBlank()} disabled={busy} />
                <RibbonButton
                  icon={<Archive />}
                  label="보관하고 비우기"
                  title="지금 목록과 수정 내용을 보관해 두고 목록을 비웁니다. 첫 화면의 '최근 작업'에서 다시 엽니다"
                  onClick={handlePutAside}
                  disabled={busy}
                />
                <RibbonButton
                  icon={<Eraser />}
                  label="모두 지우기"
                  title="모든 페이지와 수정 내용을 지웁니다 (되돌릴 수 없음)"
                  onClick={handleStartOver}
                  disabled={busy}
                  danger
                />
              </RibbonGroup>
              <RibbonGroup label="편집">
                <RibbonButton
                  icon={<Undo2 />}
                  label="실행 취소"
                  title="페이지 목록의 마지막 변경(삭제·이동·회전·복제·추가)을 되돌립니다 (Ctrl+Z)"
                  onClick={handleUndo}
                  disabled={busy || !canUndo}
                />
                <RibbonButton
                  icon={<Redo2 />}
                  label="다시 실행"
                  title="되돌린 변경을 다시 합니다 (Ctrl+Y)"
                  onClick={handleRedo}
                  disabled={busy || !canRedo}
                />
                <RibbonButton icon={<Search />} label="찾아 바꾸기" title="모든 페이지에서 글자를 찾아 바꿉니다 (Ctrl+F)" onClick={() => setFindOpen(true)} disabled={busy} />
                <RibbonButton
                  icon={<SquareCheck />}
                  label="모두 선택"
                  title="모든 페이지를 선택합니다 (Ctrl+A)"
                  onClick={() => setSelectedKeys(new Set(pages.map((p) => p.key)))}
                  disabled={busy}
                />
              </RibbonGroup>
              <RibbonGroup label="선택한 페이지">
                <RibbonButton
                  icon={<ArrowLeft />}
                  label="앞으로"
                  title="선택한 페이지를 한 칸 앞으로 옮깁니다 (Ctrl+←). 페이지 카드를 끌어 옮기거나, 오른쪽 클릭 메뉴로 맨 앞·맨 뒤·원하는 자리로 옮길 수도 있습니다."
                  onClick={() => handleMoveSelected(-1)}
                  disabled={busy || !canMoveBy(pages, selectedKeys, -1)}
                />
                <RibbonButton
                  icon={<ArrowRight />}
                  label="뒤로"
                  title="선택한 페이지를 한 칸 뒤로 옮깁니다 (Ctrl+→)"
                  onClick={() => handleMoveSelected(1)}
                  disabled={busy || !canMoveBy(pages, selectedKeys, 1)}
                />
                <RibbonButton
                  icon={<RotateCcw />}
                  label="왼쪽 회전"
                  title="선택한 페이지를 왼쪽으로 돌립니다 (Shift+R)"
                  onClick={() => handleBulkRotate(-90)}
                  disabled={busy || selectedKeys.size === 0}
                />
                <RibbonButton
                  icon={<RotateCw />}
                  label="오른쪽 회전"
                  title="선택한 페이지를 오른쪽으로 돌립니다 (R)"
                  onClick={() => handleBulkRotate(90)}
                  disabled={busy || selectedKeys.size === 0}
                />
                <RibbonButton
                  icon={<CopyPlus />}
                  label="복제"
                  title="선택한 페이지를 고친 내용째로 복사해 각각 바로 뒤에 넣습니다 (Ctrl+D)"
                  onClick={() => void handleDuplicate([...selectedKeys])}
                  disabled={busy || selectedKeys.size === 0}
                />
                <RibbonButton
                  icon={<FileOutput />}
                  label="추출"
                  title="선택한 페이지만 PDF로 내려받습니다"
                  onClick={handleExtractSelected}
                  disabled={busy || selectedKeys.size === 0}
                />
                <RibbonButton
                  icon={<Trash />}
                  label="삭제"
                  title="선택한 페이지를 목록에서 뺍니다 (Delete)"
                  onClick={handleBulkDelete}
                  disabled={busy || selectedKeys.size === 0}
                  danger
                />
              </RibbonGroup>
              <RibbonGroup label="검토·보안">
                <RibbonButton
                  icon={<EyeOff />}
                  label="개인정보 가리기"
                  title="주민등록번호·전화번호 같은 개인정보를 찾아 가리거나, 가릴 곳을 직접 그립니다. 내보낼 때 실제로 지워집니다."
                  onClick={() => setRedactOpen(true)}
                  disabled={busy}
                />
                <RibbonButton
                  icon={<ScanText />}
                  label="글자 인식(OCR)"
                  title="스캔한 쪽의 글자를 읽어, 내보낸 PDF에서 검색·복사할 수 있게 합니다. 선택한 쪽(없으면 모든 쪽)을 차례로 인식하며 쪽당 2초쯤 걸립니다. 이 PC 안에서만 처리합니다."
                  onClick={() => void handleOcr()}
                  disabled={busy}
                />
                <RibbonButton
                  icon={<Files />}
                  label="파일마다 처리"
                  title="연 파일마다 글자 인식·용량 줄이기를 하고, 파일마다 따로 PDF·엑셀로 저장합니다 (여러 개면 ZIP 하나로)"
                  onClick={() => {
                    ocrStop.current = false
                    setBatchOpen(true)
                  }}
                  disabled={busy || pages.length === 0}
                />
                <RibbonButton
                  icon={<GitCompareArrows />}
                  label="문서 비교"
                  title="올린 두 PDF(이전 판과 새 판)의 바뀐 글자를 나란히 표시합니다"
                  onClick={() => setCompareOpen(true)}
                  disabled={busy}
                />
              </RibbonGroup>
              <RibbonGroup label="내보내기">
                <RibbonButton
                  icon={<FileImage />}
                  label="이미지로 변환"
                  title="켜면 각 페이지를 그림으로 바꿔 내보냅니다. 수정 전 글자가 파일에서 완전히 사라지지만 검색·복사는 할 수 없습니다."
                  onClick={() => setFlatten((f) => !f)}
                  pressed={flatten}
                />
                <RibbonButton
                  icon={<Shrink />}
                  label="용량 줄이기"
                  title="켜면 내보낼 때 스캔·사진 같은 그림을 150dpi로 다시 저장해 파일을 작게 만듭니다(메일·서류 제출용). 글자와 표·도형은 그대로이고, 작은 그림·흑백 스캔은 손대지 않습니다."
                  onClick={() => setCompress((c) => !c)}
                  pressed={compress}
                />
                <RibbonButton
                  icon={<Printer />}
                  label="인쇄"
                  title={
                    selectedKeys.size > 0
                      ? `선택한 ${selectedKeys.size}쪽을 인쇄합니다 (Ctrl+P)`
                      : '목록의 모든 페이지를 내보낼 때와 같은 모습으로 인쇄합니다 (Ctrl+P)'
                  }
                  onClick={() => void handlePrint()}
                  disabled={busy}
                />
                <RibbonButton
                  icon={<ImageDown />}
                  label="사진으로 내보내기"
                  title={
                    selectedKeys.size > 0
                      ? `선택한 ${selectedKeys.size}쪽을 JPG·PNG 사진으로 저장합니다`
                      : '모든 페이지를 JPG·PNG 사진으로 저장합니다 (여러 쪽은 ZIP 파일 하나로)'
                  }
                  onClick={() => askImageExport()}
                  disabled={busy}
                />
                <RibbonButton
                  icon={<FileSpreadsheet />}
                  label="엑셀로 내보내기"
                  title={
                    selectedKeys.size > 0
                      ? `선택한 ${selectedKeys.size}쪽을 엑셀 파일(.xlsx)로 저장합니다. 쪽마다 시트 한 장, 표는 PDF와 같은 모양으로`
                      : '모든 페이지를 엑셀 파일(.xlsx)로 저장합니다. 쪽마다 시트 한 장, 표는 PDF와 같은 모양으로'
                  }
                  onClick={() => void handleExcel()}
                  disabled={busy}
                />
                <RibbonButton
                  icon={<Table2 />}
                  label="표 다듬기"
                  title={
                    (selectedKeys.size > 0 ? `선택한 ${selectedKeys.size}쪽` : '모든 페이지') +
                    '의 표를 엑셀 화면으로 열어 값·수식·칸을 고친 뒤 엑셀 파일로 저장합니다 (PDF 원본은 그대로)'
                  }
                  onClick={() => void handlePolish()}
                  disabled={busy}
                />
                <RibbonButton
                  icon={<FileLock />}
                  label="암호 걸어 내보내기"
                  title="열 때 암호를 묻는 PDF로 내려받습니다 (AES-256). 암호가 걸려 있던 문서는 원래의 인쇄·복사 제한도 다시 걸립니다."
                  onClick={() => void handleExport(true)}
                  disabled={busy}
                />
                <RibbonButton
                  icon={<FileDown />}
                  label="PDF로 내보내기"
                  title="목록의 모든 페이지를 순서대로 하나의 PDF로 내려받습니다 (Ctrl+S)"
                  onClick={() => void handleExport()}
                  disabled={busy}
                  primary
                />
              </RibbonGroup>
            </RibbonPanel>
          </div>
          <PageGrid
            pages={pages}
            selectedKeys={selectedKeys}
            busy={busy}
            onReorder={changePages}
            onRotate={handleRotate}
            onDelete={handleDelete}
            onToggleSelect={handleToggleSelect}
            onInsertBlankAfter={handleInsertBlank}
            onExtract={handleExtractOne}
            onDuplicate={(key) => void handleDuplicate([key])}
            onEditText={setEditingKey}
            onRedact={setDrawKey}
            onStraighten={(key) => void handleStraighten(selectedKeys.has(key) ? [...selectedKeys] : [key])}
            onOcr={(key) => {
              const page = pages.find((p) => p.key === key)
              if (page) void handleOcr([page])
            }}
            onPrint={(key) => {
              const page = pages.find((p) => p.key === key)
              if (page) void handlePrint([page])
            }}
            onSaveImage={(key) => {
              const page = pages.find((p) => p.key === key)
              if (page) askImageExport([page])
            }}
            onSaveExcel={(key) => {
              const page = pages.find((p) => p.key === key)
              if (page) void handleExcel([page])
            }}
          />
        </>
      )}

      {editingPage && (
        <TextEditor
          key={`${editingPage.key}-${editorNonce}`}
          page={editingPage}
          position={editingIndex}
          total={pages.length}
          onNavigate={handleNavigateEditor}
          onClose={handleCloseEditor}
          onEdited={handleTextEdited}
          onExport={() => handleExport()}
          onPrint={() => handlePrint(pages)}
          flatten={flatten}
          onFlattenChange={setFlatten}
          onOpenFind={(query) => {
            setFindQuery(query ?? '')
            setFindOpen(true)
          }}
          installedFonts={info?.installedFonts}
          unreadableFonts={info?.unreadableFonts ?? []}
          onFontsReloaded={handleFontsReloaded}
          onRecognized={(key, lines) => setPages((prev) => prev.map((p) => (p.key === key ? { ...p, ocrLines: lines } : p)))}
          onRecognizeAll={() => handleOcr(pages)}
          onStraightenFirst={async () => {
            // The page in the editor, set straight before it is read: the editor opens again on the straight copy
            // and recognizes it there.
            if (settings?.straightenOnOcr === false || busy) return false
            const { copies, upright } = await straighten([editingPage])
            const copy = copies.get(editingPage.key)
            if (!copy) return false
            setRecognizeOnOpen({ key: copy.key, upright: upright > 0 })
            setEditingKey(copy.key)
            return true
          }}
          recognizeOnOpen={recognizeOnOpen?.key === editingPage.key ? { upright: recognizeOnOpen.upright } : undefined}
          onRecognizedOnOpen={() => setRecognizeOnOpen(null)}
          ocrProgress={ocrProgress}
          onStopRecognize={() => (ocrStop.current = true)}
          busy={busy}
        />
      )}

      {findOpen && (
        <FindReplaceDialog
          pages={pages}
          initialQuery={findQuery}
          onClose={() => setFindOpen(false)}
          onOpenPage={setEditingKey}
          onReplaced={handleReplaced}
        />
      )}

      {compareOpen && <CompareView pages={pages} onClose={() => setCompareOpen(false)} />}

      {batchOpen && (
        <BatchDialog
          files={filesOf(pages).map((f) => ({ name: f.name, pages: f.pages.length }))}
          onRun={(choice) => void handleBatch(choice)}
          onClose={() => setBatchOpen(false)}
        />
      )}

      {scanSettingsOpen && settings && (
        <ScanSettingsDialog
          settings={settings}
          onSave={async (next) => setSettings(await saveSettings(next))}
          onClose={() => setScanSettingsOpen(false)}
        />
      )}

      {retentionOpen && settings && (
        <RetentionDialog
          days={settings.retentionDays}
          onSave={async (days) => setSettings(await saveSettings({ ...settings, retentionDays: days }))}
          onClose={() => setRetentionOpen(false)}
        />
      )}

      {polish && <SheetPolish sheets={polish.sheets} fileName={polish.fileName} onClose={() => setPolish(null)} />}
      {hwpDoc && <HwpEditor key={hwpDoc.key} file={hwpDoc.file} onClose={() => setHwpDoc(null)} />}

      {redactOpen && drawKey === null && (
        <RedactDialog
          pages={pages}
          onClose={() => setRedactOpen(false)}
          onDraw={setDrawKey}
          onChanged={handleRedactionsChanged}
        />
      )}

      {drawKey !== null && (
        <RedactEditor
          pages={pages}
          startKey={drawKey}
          onClose={() => setDrawKey(null)}
          onChanged={(page) => handleRedactionsChanged([page])}
        />
      )}

      {passwordAsk && (
        <PasswordDialog
          key={`${passwordAsk.fileName}-${passwordAsk.wrong}`}
          fileName={passwordAsk.fileName}
          wrong={passwordAsk.wrong}
          onSubmit={(password) => passwordAsk.resolve(password)}
          onCancel={() => passwordAsk.resolve(null)}
        />
      )}

      {lockAsk && (
        <LockDialog
          fromProtected={lockAsk.fromProtected}
          onLock={(password) => lockAsk.resolve({ password })}
          onPlain={() => lockAsk.resolve('plain')}
          onCancel={() => lockAsk.resolve(null)}
        />
      )}

      {imageAsk && (
        <ImageExportDialog
          pageCount={imageAsk.pages.length}
          picked={imageAsk.picked}
          fromProtected={keptPermissions(imageAsk.pages) !== undefined}
          initial={imageChoice}
          onExport={(choice) => void handleImageExport(imageAsk.pages, choice)}
          onCancel={() => setImageAsk(null)}
        />
      )}
    </div>
  )
}

export default App
