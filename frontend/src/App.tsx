import { useCallback, useEffect, useRef, useState } from 'react'
import './App.css'
import {
  createBlankDocument,
  deleteDocument,
  exportPdf,
  fetchAppInfo,
  fetchDocumentInfo,
  fetchUpdate,
  installUpdate,
  reloadFonts,
  uploadDocument,
} from './api'
import { reloadMessage } from './fontHelp'
import { exportFileName } from './exportName'
import { keyCode } from './keys'
import { dragHasFiles, pickPdfs } from './pdfFiles'
import { clearSession, loadSession, saveSession } from './session'
import { readSkippedVersion, shouldAnnounce, skipVersion } from './updateNotice'
import { FindReplaceDialog } from './components/FindReplaceDialog'
import { UpdateBanner } from './components/UpdateBanner'
import { PageGrid } from './components/PageGrid'
import { TextEditor } from './components/TextEditor'
import { UploadZone } from './components/UploadZone'
import { RotateLeftIcon, RotateRightIcon } from './components/icons'
import type { AppInfo, PageItem, ReplaceResult, UpdateInfo } from './types'

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
  const [error, setError] = useState<string | null>(null)
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
        document.title = `WINTECH_PDF v${loaded.version}`
      })
      .catch(() => setInfo(null))
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
    const { pdfs, skipped } = pickPdfs(files)
    const problems: string[] = []
    if (skipped.length > 0) problems.push(`PDF가 아니라서 건너뛴 파일: ${skipped.join(', ')}`)
    if (pdfs.length === 0) {
      setError(problems.join('\n') || null)
      return
    }
    setBusy(true)
    setError(null)
    // One bad file must not keep the others from being opened.
    for (const file of pdfs) {
      try {
        const uploaded = await uploadDocument(file)
        const newPages: PageItem[] = Array.from({ length: uploaded.pageCount }, (_, i) => ({
          key: `${uploaded.documentId}-${i}`,
          documentId: uploaded.documentId,
          sourceFileName: uploaded.fileName,
          pageIndex: i,
          rotation: 0,
          textRevision: 0,
        }))
        setPages((prev) => [...prev, ...newPages])
      } catch (e) {
        problems.push(e instanceof Error ? e.message : `업로드 중 오류가 발생했습니다: ${file.name}`)
      }
    }
    setError(problems.length > 0 ? problems.join('\n') : null)
    setBusy(false)
  }

  /**
   * A PDF dropped anywhere on the window is opened like one dropped on the upload box; without this the
   * browser would navigate away to show the file and the work in progress would look lost.
   */
  const filesDropped = useRef<(files: File[]) => void>(() => {})
  useEffect(() => {
    filesDropped.current = (files) => {
      if (editingKey === null && !busy) void handleFiles(files)
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

  function handleBulkDelete() {
    setPages((prev) => prev.filter((p) => !selectedKeys.has(p.key)))
    clearSelection()
  }

  async function handleExtract(targetPages: PageItem[], fileName: string) {
    if (targetPages.length === 0) return
    setBusy(true)
    setError(null)
    try {
      const blob = await exportPdf(targetPages, fileName, flatten)
      downloadBlob(blob, fileName)
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
  async function handleExport() {
    if (pages.length === 0) return
    setBusy(true)
    setError(null)
    try {
      const fileName = exportFileName(pages)
      const blob = await exportPdf(pages, fileName, flatten)
      downloadBlob(blob, fileName)
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
  function handleFontsReloaded(installedFonts: number) {
    setInfo((prev) => (prev ? { ...prev, installedFonts } : prev))
    setPages((prev) => prev.map((p) => ({ ...p, textRevision: p.textRevision + 1 })))
  }

  async function handleReloadFontsFromList() {
    if (fontsBusy) return
    setFontsBusy(true)
    try {
      const before = info?.installedFonts
      const next = await reloadFonts()
      handleFontsReloaded(next.installedFonts)
      setFontsNote(reloadMessage(before, next.installedFonts))
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

  const startOverButton = (
    <button type="button" className="danger" onClick={handleStartOver} disabled={busy} title="모든 페이지와 수정 내용을 지웁니다 (되돌릴 수 없음)">
      모두 지우기
    </button>
  )

  /**
   * Keyboard shortcuts of the page list (the editor has its own). Called through a ref so it always
   * sees the current pages and selection.
   */
  const shortcut = useRef<(e: KeyboardEvent) => void>(() => {})
  useEffect(() => {
    shortcut.current = (e) => {
      if (e.isComposing || e.keyCode === 229 || pages.length === 0) return
      const el = e.target instanceof HTMLElement ? e.target : null
      const inField = el?.closest('input, select, textarea, [contenteditable="true"], [contenteditable="plaintext-only"]') != null
      const ctrl = e.ctrlKey || e.metaKey
      const code = keyCode(e)
      if (ctrl && (code === 'KeyF' || code === 'KeyH')) {
        e.preventDefault()
        setFindOpen(true)
        return
      }
      if (findOpen || editingKey !== null || inField) return
      if (ctrl && code === 'KeyS') {
        e.preventDefault()
        void handleExport()
      } else if (ctrl && code === 'KeyA') {
        e.preventDefault()
        setSelectedKeys(new Set(pages.map((p) => p.key)))
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

  const flattenOption = (
    <label className="flatten-option" title="각 페이지를 그림으로 바꿔 내보냅니다. 수정 전 글자가 파일에서 완전히 사라지지만 검색·복사는 할 수 없습니다.">
      <input type="checkbox" checked={flatten} onChange={(e) => setFlatten(e.target.checked)} />
      이미지로 변환 (수정 전 글자 완전 제거)
    </label>
  )

  return (
    <div className="app">
      <header className="app-header">
        <h1>PDF 편집기</h1>
        <p className="subtitle">텍스트 수정 · 페이지 순서 변경 · 회전 · 삭제 · 삽입 · 추출 · 여러 PDF 합치기</p>
        <p className="app-meta">
          {info
            ? `v${info.version} · 로컬 글꼴 ${info.installedFonts.toLocaleString('ko-KR')}개 읽음`
            : '글꼴 확인 중…'}{' '}
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
          {selectedKeys.size > 0 ? (
            <div className="toolbar selection-toolbar">
              <span>{selectedKeys.size}개 선택됨</span>
              <div className="toolbar-actions">
                {startOverButton}
                <button type="button" onClick={() => handleBulkRotate(-90)} disabled={busy} title="왼쪽으로 회전 (Shift+R)">
                  <RotateLeftIcon /> 회전
                </button>
                <button type="button" onClick={() => handleBulkRotate(90)} disabled={busy} title="오른쪽으로 회전 (R)">
                  <RotateRightIcon /> 회전
                </button>
                <button type="button" onClick={handleExtractSelected} disabled={busy}>
                  선택 항목 추출
                </button>
                <button type="button" className="danger" onClick={handleBulkDelete} disabled={busy} title="삭제 (Delete)">
                  선택 항목 삭제
                </button>
                <button type="button" onClick={clearSelection} disabled={busy} title="선택 해제 (Esc)">
                  선택 해제
                </button>
              </div>
            </div>
          ) : (
            <div className="toolbar">
              <span>{pages.length}페이지</span>
              <div className="toolbar-actions">
                {startOverButton}
                {flattenOption}
                <button type="button" onClick={() => setFindOpen(true)} disabled={busy} title="찾아 바꾸기 (Ctrl+F)">
                  찾아 바꾸기
                </button>
                <button type="button" onClick={() => handleInsertBlank()} disabled={busy}>
                  + 빈 페이지 추가
                </button>
                <button type="button" className="primary" onClick={handleExport} disabled={busy} title="PDF로 내보내기 (Ctrl+S)">
                  PDF로 내보내기
                </button>
              </div>
            </div>
          )}
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
          onExport={handleExport}
          flatten={flatten}
          onFlattenChange={setFlatten}
          onOpenFind={() => setFindOpen(true)}
          installedFonts={info?.installedFonts}
          onFontsReloaded={handleFontsReloaded}
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
    </div>
  )
}

export default App
