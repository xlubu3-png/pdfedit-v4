import { useRef, useState } from 'react'
import type { DragEvent } from 'react'

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
        accept="application/pdf"
        multiple
        hidden
        onChange={(e) => {
          const files = Array.from(e.target.files ?? [])
          if (files.length > 0) onFiles(files)
          e.target.value = ''
        }}
      />
      <p>{busy ? '업로드 중…' : 'PDF 파일을 끌어다 놓거나 클릭해서 선택하세요'}</p>
      <p className="hint">여러 파일을 선택하면 순서대로 이어붙입니다</p>
    </div>
  )
}
