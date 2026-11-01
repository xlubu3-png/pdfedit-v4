import { useEffect, useRef, useState } from 'react'
import { nextUnsure } from '../ocr'
import type { OcrText } from '../types'

interface OcrFixBarProps {
  lines: OcrText[]
  /** The line being corrected. */
  index: number
  /** Lines read with less confidence than this are the ones to check. */
  unsure: number
  onSave: (index: number, text: string) => Promise<void>
  /** Go to another line (it is picked on the page). */
  onGo: (index: number) => void
  onClose: () => void
}

/**
 * Corrects what recognition read off a scanned line, the scan left as it is: the corrected text is what search,
 * copying and the export's invisible text say. Steps from one line read with little confidence (orange) to the next.
 */
export function OcrFixBar({ lines, index, unsure, onSave, onGo, onClose }: OcrFixBarProps) {
  const line = lines[index]
  const [text, setText] = useState(line?.text ?? '')
  const [saving, setSaving] = useState(false)
  const input = useRef<HTMLInputElement>(null)

  // Shown anew for every line (the editor keys it by the line), with its text ready to type over.
  useEffect(() => {
    input.current?.focus()
    input.current?.select()
  }, [])

  if (!line) return null
  const previous = nextUnsure(lines, index, -1, unsure)
  const next = nextUnsure(lines, index, 1, unsure)
  const left = lines.filter((l) => l.score < unsure).length

  async function save() {
    const value = text.trim()
    if (!value) return
    setSaving(true)
    try {
      if (value !== line.text || line.score < unsure) await onSave(index, value)
      // On to the next line to check, if there is one.
      const after = nextUnsure(lines, index, 1, unsure)
      if (after !== null) onGo(after)
      else onClose()
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="te-fixbar" role="dialog" aria-label="인식 글자 고치기">
      <b>인식 글자 고치기</b>
      <span className="te-fixbar-where">
        {index + 1}번째 줄{line.score < unsure ? ` · 확신도 ${Math.round(line.score * 100)}%` : ''} · 확인할 줄 {left}개
      </span>
      <input
        ref={input}
        value={text}
        aria-label="이 줄의 글자"
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          e.stopPropagation()
          if (e.key === 'Enter') {
            e.preventDefault()
            void save()
          } else if (e.key === 'Escape') {
            e.preventDefault()
            onClose()
          }
        }}
      />
      <button type="button" onClick={() => void save()} disabled={saving || !text.trim()} title="고친 글자를 저장하고 다음 확인할 줄로 (Enter)">
        저장
      </button>
      <button type="button" onClick={() => previous !== null && onGo(previous)} disabled={previous === null} title="앞의 확인할 줄(주황 점선)로">
        ◀ 이전
      </button>
      <button type="button" onClick={() => next !== null && onGo(next)} disabled={next === null} title="다음 확인할 줄(주황 점선)로">
        다음 ▶
      </button>
      <button type="button" onClick={onClose} aria-label="닫기" title="닫기 (Esc)">
        ✕
      </button>
      <span className="te-fixbar-note">스캔 그림은 그대로 두고, 검색·복사·내보낸 PDF의 글자만 바뀝니다.</span>
    </div>
  )
}
