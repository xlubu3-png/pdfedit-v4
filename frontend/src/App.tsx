import { useCallback, useEffect, useRef, useState } from 'react'
import './App.css'
import {
  ApiError,
  createBlankDocument,
  deleteDocument,
  duplicatePage,
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
import { ocrMessage, recognizeAll } from './ocr'
import { keyCode } from './keys'
import { dragHasFiles, pickPdfs } from './pdfFiles'
import { clearSession, loadSession, saveSession } from './session'
import { readSkippedVersion, shouldAnnounce, skipVersion } from './updateNotice'
import { CompareView } from './components/CompareView'
import { FindReplaceDialog } from './components/FindReplaceDialog'
import { HwpEditor } from './components/HwpEditor'
import { LockDialog } from './components/LockDialog'
import { PasswordDialog } from './components/PasswordDialog'
import { RetentionDialog } from './components/RetentionDialog'
import { RedactDialog } from './components/RedactDialog'
import { RedactEditor } from './components/RedactEditor'
import { UpdateBanner } from './components/UpdateBanner'
import { PageGrid } from './components/PageGrid'
import { TextEditor } from './components/TextEditor'
import { UploadZone } from './components/UploadZone'
import { RibbonButton, RibbonGroup, RibbonPanel } from './components/Ribbon'
import {
  CopyPlus,
  Eraser,
  EyeOff,
  FileDown,
  FileImage,
  FileLock,
  FileOutput,
  FilePlus,
  FolderOpen,
  GitCompareArrows,
  Printer,
  RotateCcw,
  RotateCw,
  ScanText,
  Search,
  SquareCheck,
  Trash,
} from 'lucide-react'
import type { AppInfo, AppSettings, PageItem, ReplaceResult, UpdateInfo, UploadResponse } from './types'

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

function downloadBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  a.click()
  URL.revokeObjectURL(url)
}

const rotate = (p: PageItem, delta: 90 | -90): PageItem => ({
  ...p,
  rotation: (((p.rotation + delta) % 360) + 360) % 360 as PageItem['rotation'],
})

function App() {
  const [pages, setPages] = useState<PageItem[]>([])
  const [selectedKeys, setSelectedKeys] = useState<Set<string>>(new Set())
  const [busy, setBusy] = useState(false)
  const [editingKey, setEditingKey] = useState<string | null>(null)
  /** Changes whenever the open editor must start over, e.g. after a replace changed its page behind its back. */
  const [editorNonce, setEditorNonce] = useState(0)
  const [info, setInfo] = useState<AppInfo | null>(null)
  const [settings, setSettings] = useState<AppSettings | null>(null)
  const [retentionOpen, setRetentionOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [passwordAsk, setPasswordAsk] = useState<PasswordAsk | null>(null)
  const [lockAsk, setLockAsk] = useState<LockAsk | null>(null)
  /** Just-opened documents that look scanned: the screen offers to recognize their text. */
  const [scanSuggest, setScanSuggest] = useState<{ files: string[]; pages: PageItem[] } | null>(null)
  /** How far a text recognition run is; null when none runs. */
  const [ocrProgress, setOcrProgress] = useState<{ done: number; total: number } | null>(null)
  const ocrStop = useRef(false)
  /** A note about the last upload or export (a file opened with its password, what a redaction did). */
  const [notice, setNotice] = useState<string | null>(null)
  const [redactOpen, setRedactOpen] = useState(false)
  const [compareOpen, setCompareOpen] = useState(false)
  /** A Hangul document open in the HWP editor, or null. */
  const [hwpFile, setHwpFile] = useState<File | null>(null)
  /** The page the redaction areas are being drawn on, or null. */
  const [drawKey, setDrawKey] = useState<string | null>(null)
  /** Export every page as a picture, so nothing of the original (covered-up) text stays in the file. */
  const [flatten, setFlatten] = useState(false)
  const [findOpen, setFindOpen] = useState(false)
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
      .then(setUpdate)
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
  }, [])

  useEffect(() => {
    if (!restored) return
    if (pages.length === 0 && !hadPages.current) return
    hadPages.current = pages.length > 0
    saveSession(pages)
  }, [pages, restored])

  async function handleFiles(files: File[]) {
    const { pdfs, hwps, skipped } = pickPdfs(files)
    const problems: string[] = []
    if (skipped.length > 0) problems.push(`PDF·한글 문서가 아니라서 건너뛴 파일: ${skipped.join(', ')}`)
    if (hwps.length > 0) {
      setHwpFile(hwps[0])
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
        setPages((prev) => [...prev, ...newPages])
        if (uploaded.scanned) {
          scanned.files.push(uploaded.fileName)
          scanned.pages.push(...newPages)
        }
      } catch (e) {
        problems.push(e instanceof Error ? e.message : `업로드 중 오류가 발생했습니다: ${file.name}`)
      }
    }
    setError(problems.length > 0 ? problems.join('\n') : null)
    if (unlocked.length > 0) {
      setNotice(`암호를 풀어서 열었습니다: ${unlocked.join(', ')}. 내보낼 때 암호를 다시 걸 수 있습니다.`)
    }
    setScanSuggest(scanned.pages.length > 0 ? scanned : null)
    setBusy(false)
  }

  /**
   * Recognizes the text of scanned pages, one after another: the given ones, else the selected ones, else all.
   * The result is kept by the server and goes into exports as invisible text.
   */
  async function handleOcr(target?: PageItem[]) {
    const chosen = target ?? (selectedKeys.size > 0 ? pages.filter((p) => selectedKeys.has(p.key)) : pages)
    if (chosen.length === 0 || busy) return
    setBusy(true)
    setError(null)
    setScanSuggest(null)
    ocrStop.current = false
    try {
      const summary = await recognizeAll(
        chosen,
        recognizePage,
        (done, total) => setOcrProgress({ done, total }),
        () => ocrStop.current,
      )
      setPages((prev) =>
        prev.map((p) => (p.key in summary.linesByKey ? { ...p, ocrLines: summary.linesByKey[p.key] } : p)),
      )
      setNotice(ocrMessage(summary))
    } catch (e) {
      setError(e instanceof Error ? e.message : '글자를 인식하지 못했습니다')
    } finally {
      setOcrProgress(null)
      setBusy(false)
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
      if (editingKey === null && hwpFile === null && !busy) void handleFiles(files)
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
    setPages((prev) => prev.map((p) => (p.key === key ? rotate(p, delta) : p)))
  }

  function handleDelete(key: string) {
    setPages((prev) => prev.filter((p) => p.key !== key))
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
      setPages((prev) => {
        if (!afterKey) return [...prev, newPage]
        const index = prev.findIndex((p) => p.key === afterKey)
        if (index === -1) return [...prev, newPage]
        const next = [...prev]
        next.splice(index + 1, 0, newPage)
        return next
      })
    } catch (e) {
      setError(e instanceof Error ? e.message : '빈 페이지 추가 중 오류가 발생했습니다')
    } finally {
      setBusy(false)
    }
  }

  function handleBulkRotate(delta: 90 | -90) {
    setPages((prev) => prev.map((p) => (selectedKeys.has(p.key) ? rotate(p, delta) : p)))
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
      setPages((prev) => prev.flatMap((p) => (copies.has(p.key) ? [p, copies.get(p.key)!] : [p])))
    } catch (e) {
      setError(e instanceof Error ? e.message : '페이지를 복제하지 못했습니다')
    } finally {
      setBusy(false)
    }
  }

  function handleBulkDelete() {
    setPages((prev) => prev.filter((p) => !selectedKeys.has(p.key)))
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

  function exportedNotice(redaction: Parameters<typeof redactionMessage>[0], lock: ExportLock | undefined) {
    const lines = [lock ? '암호를 걸어 내보냈습니다.' : null, redactionMessage(redaction)].filter(Boolean)
    return lines.length > 0 ? lines.join(' ') : null
  }

  async function handleExtract(targetPages: PageItem[], fileName: string) {
    if (targetPages.length === 0) return
    const lock = await askLock(targetPages)
    if (lock === null) return
    setBusy(true)
    setError(null)
    try {
      const { blob, redaction } = await exportPdf(targetPages, fileName, flatten, lock)
      downloadBlob(blob, fileName)
      setNotice(exportedNotice(redaction, lock))
    } catch (e) {
      setError(e instanceof Error ? e.message : '추출 중 오류가 발생했습니다')
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
      setError(e instanceof Error ? e.message : '인쇄할 문서를 만들지 못했습니다')
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
      const { blob, redaction } = await exportPdf(pages, fileName, flatten, lock)
      downloadBlob(blob, fileName)
      setNotice(exportedNotice(redaction, lock))
    } catch (e) {
      setError(e instanceof Error ? e.message : '내보내기 중 오류가 발생했습니다')
    } finally {
      setBusy(false)
    }
  }

  const editingIndex = editingKey === null ? -1 : pages.findIndex((p) => p.key === editingKey)
  const editingPage = editingIndex >= 0 ? pages[editingIndex] : null

  const handleTextEdited = useCallback((key: string) => {
    setPages((prev) => prev.map((p) => (p.key === key ? { ...p, textRevision: p.textRevision + 1 } : p)))
  }, [])

  const handleCloseEditor = useCallback(() => setEditingKey(null), [])

  /** Redaction areas changed on these pages: their thumbnails show the black boxes again. */
  const handleRedactionsChanged = useCallback((changed: { documentId: string; pageIndex: number }[]) => {
    const keys = new Set(changed.map((c) => `${c.documentId}:${c.pageIndex}`))
    setPages((prev) =>
      prev.map((p) => (keys.has(`${p.documentId}:${p.pageIndex}`) ? { ...p, textRevision: p.textRevision + 1 } : p)),
    )
  }, [])

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
      setError(e instanceof Error ? e.message : '글꼴을 다시 읽지 못했습니다')
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
      setUpdateNote(e instanceof Error ? e.message : '업데이트를 확인하지 못했습니다')
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
      setError(e instanceof Error ? e.message : '업데이트하지 못했습니다')
    }
  }

  function handleSkipUpdate() {
    if (!update?.latest) return
    skipVersion(update.latest)
    setSkippedVersion(update.latest)
  }

  function handleStartOver() {
    if (!window.confirm('모든 페이지와 수정 내용이 삭제되며 되돌릴 수 없습니다. 저장된 문서 사본도 함께 삭제됩니다. 모두 지울까요?')) return
    // The server keeps a copy of every uploaded file; starting over is the moment to remove them.
    for (const documentId of new Set(pages.map((p) => p.documentId))) void deleteDocument(documentId)
    setPages([])
    setSelectedKeys(new Set())
    setEditingKey(null)
    setRestoredNote(null)
    clearSession()
  }

  /**
   * Keyboard shortcuts of the page list (the editor has its own). Called through a ref so it always
   * sees the current pages and selection.
   */
  const shortcut = useRef<(e: KeyboardEvent) => void>(() => {})
  useEffect(() => {
    shortcut.current = (e) => {
      if (e.isComposing || e.keyCode === 229 || pages.length === 0 || passwordAsk !== null || lockAsk !== null) return
      const el = e.target instanceof HTMLElement ? e.target : null
      // A page's tick box is not somewhere to type: Ctrl+D or Delete right after ticking one acts on the selection.
      const inField =
        el?.closest(
          'input:not([type=checkbox]), select, textarea, [contenteditable="true"], [contenteditable="plaintext-only"]',
        ) != null
      const ctrl = e.ctrlKey || e.metaKey
      const code = keyCode(e)
      if (ctrl && (code === 'KeyF' || code === 'KeyH')) {
        e.preventDefault()
        setFindOpen(true)
        return
      }
      if (findOpen || redactOpen || compareOpen || hwpFile !== null || drawKey !== null || editingKey !== null || inField) return
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
        <p className="subtitle">PDF · 한글(HWP/HWPX) 편집 · 페이지 정리와 합치기 · 문서 비교 · 개인정보 가리기</p>
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
        </p>
      </header>

      <UploadZone onFiles={handleFiles} busy={busy} />

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

      {scanSuggest && !ocrProgress && (
        <div className="info-banner update-banner" role="status">
          <span>
            스캔한 문서로 보입니다: {scanSuggest.files.join(', ')}. 글자 인식(OCR)을 하면 내보낸 PDF에서 글자를
            검색·복사할 수 있습니다 (쪽당 2초쯤).
          </span>
          <button type="button" className="primary" onClick={() => void handleOcr(scanSuggest.pages)} disabled={busy}>
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
            글자 인식 중… {ocrProgress.done}/{ocrProgress.total}쪽
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
                  icon={<Eraser />}
                  label="모두 지우기"
                  title="모든 페이지와 수정 내용을 지웁니다 (되돌릴 수 없음)"
                  onClick={handleStartOver}
                  disabled={busy}
                  danger
                />
              </RibbonGroup>
              <RibbonGroup label="편집">
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
            onReorder={setPages}
            onRotate={handleRotate}
            onDelete={handleDelete}
            onToggleSelect={handleToggleSelect}
            onInsertBlankAfter={handleInsertBlank}
            onExtract={handleExtractOne}
            onDuplicate={(key) => void handleDuplicate([key])}
            onEditText={setEditingKey}
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
          onOpenFind={() => setFindOpen(true)}
          installedFonts={info?.installedFonts}
          unreadableFonts={info?.unreadableFonts ?? []}
          onFontsReloaded={handleFontsReloaded}
          onRecognized={(key, lines) => setPages((prev) => prev.map((p) => (p.key === key ? { ...p, ocrLines: lines } : p)))}
          busy={busy}
        />
      )}

      {findOpen && (
        <FindReplaceDialog
          pages={pages}
          onClose={() => setFindOpen(false)}
          onOpenPage={setEditingKey}
          onReplaced={handleReplaced}
        />
      )}

      {compareOpen && <CompareView pages={pages} onClose={() => setCompareOpen(false)} />}

      {retentionOpen && settings && (
        <RetentionDialog
          days={settings.retentionDays}
          onSave={async (days) => setSettings(await saveSettings({ ...settings, retentionDays: days }))}
          onClose={() => setRetentionOpen(false)}
        />
      )}

      {hwpFile && <HwpEditor key={`${hwpFile.name}-${hwpFile.lastModified}`} file={hwpFile} onClose={() => setHwpFile(null)} />}

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
    </div>
  )
}

export default App
