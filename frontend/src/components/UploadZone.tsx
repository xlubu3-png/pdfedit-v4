import { useRef, useState } from 'react'
import type { DragEvent } from 'react'
import { OPENABLE_FILES } from '../pdfFiles'

interface UploadZoneProps {
  onFiles: (files: File[]) => void
  busy: boolean
}

export function UploadZone({ onFiles, busy }: UploadZoneProps) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [isDragOver, setIsDragOver] = useState(false)

  function handleDrop(e: DragEvent<HTMLDivElement>) {
    e.preventDefault()
    setIsDragOver(false)
    const files = Array.from(e.dataTransfer.files)
    if (files.length > 0) onFiles(files)
  }

  return (
    <div
      className={`upload-zone${isDragOver ? ' drag-over' : ''}${busy ? ' busy' : ''}`}
      onDragOver={(e) => {
        e.preventDefault()
        setIsDragOver(true)
      }}
      onDragLeave={() => setIsDragOver(false)}
      onDrop={handleDrop}
      onClick={() => !busy && inputRef.current?.click()}
    >
      <input
        ref={inputRef}
        type="file"
        accept={OPENABLE_FILES}
        multiple
        hidden
        onChange={(e) => {
          const files = Array.from(e.target.files ?? [])
          if (files.length > 0) onFiles(files)
          e.target.value = ''
        }}
      />
      <p>{busy ? '업로드 중…' : 'PDF, 한글(HWP·HWPX), Word·Excel·PowerPoint, 그림(JPG·PNG·TIFF) 파일을 끌어다 놓거나 클릭해서 선택하세요'}</p>
      <p className="hint">PDF는 여러 개를 고르면 순서대로 이어붙이고, 한글 문서는 한글 편집 화면에서 열립니다</p>
      <p className="hint">Word(docx)·Excel(xlsx)·PowerPoint(pptx)는 PC의 MS Office로 PDF로 바꿔 엽니다(원래 파일은 그대로)</p>
      <p className="hint">사진·스캔·팩스 그림 파일은 그림 한 장이 한 쪽인 PDF로 엽니다(여러 쪽 TIFF 팩스도)</p>
    </div>
  )
}
