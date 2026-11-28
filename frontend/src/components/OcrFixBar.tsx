import { useEffect, useRef, useState } from 'react'
import type { OcrCandidate } from '../api'
import { nextUnsure } from '../ocr'
import type { OcrText } from '../types'

/** A letter read with less confidence than this is marked to check (orange). */
const UNSURE_LETTER = 0.6

interface OcrFixBarProps {
  lines: OcrText[]
  /** The line being corrected. */
  index: number
  /** Lines read with less confidence than this are the ones to check. */
  unsure: number
  onSave: (index: number, text: string) => Promise<void>
  /**
   * The scan the line was read off, to show its piece beside the text: the page's picture and where the line is on
   * it (points from the page's top-left).
   */
  scan?: { url: string; pageWidth: number; pageHeight: number; left: number; top: number; width: number; height: number }
  /** The line's likeliest readings, read again off the scan (best first): offered to pick from. */
  candidates?: (index: number) => Promise<OcrCandidate[]>
  /** Go to another line (it is picked on the page). */
  onGo: (index: number) => void
  onClose: () => void
}

/**
 * Corrects what recognition read off a scanned line, the scan left as it is: the corrected text is what search,
 * copying and the export's invisible text say. Steps from one line read with little confidence (orange) to the next.
 */
export function OcrFixBar({ lines, index, unsure, onSave, scan, candidates, onGo, onClose }: OcrFixBarProps) {
  const line = lines[index]
  const [text, setText] = useState(line?.text ?? '')
  const [saving, setSaving] = useState(false)
  const [others, setOthers] = useState<OcrCandidate[]>([])
  /** How sure the reader was of each letter of the line as read, when reading it again gave the same. */
  const [sureness, setSureness] = useState<number[] | null>(null)
  const input = useRef<HTMLInputElement>(null)

  // The other readings of a line to check, asked once it is shown (the scan is read again: a second or so).
  const unsureLine = line !== undefined && line.score < unsure
  useEffect(() => {
    if (!candidates || !unsureLine) return
    let gone = false
    const read = line?.text
    candidates(index)
      .then((found) => {
        // What was read first is in the box already: only the other readings are offered.
        if (gone) return
        setOthers(found.filter((c) => c.text !== read))
        const same = found.find((c) => c.text === read && c.confidences)
        setSureness(same?.confidences && same.confidences.length === read?.length ? same.confidences : null)
      })
      .catch(() => {})
    return () => {
      gone = true
    }
  }, [candidates, index, unsureLine, line?.text])

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
      {scan && <ScanPiece {...scan} />}
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
      {sureness && sureness.some((c) => c < UNSURE_LETTER) && (
        <span className="te-fixbar-letters" aria-label="확인할 글자">
          확인할 글자{' '}
          {[...line.text].map((ch, i) => (
            <span key={i} className={sureness[i] < UNSURE_LETTER ? 'unsure' : undefined}>
              {ch}
            </span>
          ))}
        </span>
      )}
      {others.length > 0 && (
        <span className="te-fixbar-candidates" role="group" aria-label="다른 읽기 후보">
          다른 읽기
          {others.map((c) => (
            <button
              key={c.text}
              type="button"
              className={c.text === text ? 'chosen' : undefined}
              title="이 읽기로 바꾸기 (저장은 Enter)"
              onClick={() => {
                setText(c.text)
                input.current?.focus()
              }}
            >
              {c.text}
            </button>
          ))}
        </span>
      )}
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

/** The piece of the scan a line was read off, enlarged so its letters can be checked against what was read. */
function ScanPiece({ url, pageWidth, pageHeight, left, top, width, height }: NonNullable<OcrFixBarProps['scan']>) {
  // A little of the paper round the line, and at most 560 pixels wide, at most three times as big as at 100%.
  const pad = Math.max(2, height * 0.25)
  const shownWidth = width + pad * 2
  const k = Math.min(3 * (96 / 72), 560 / shownWidth)
  return (
    <div
      className="te-fixbar-scan"
      role="img"
      aria-label="스캔 원본의 이 줄"
      style={{
        width: shownWidth * k,
        height: (height + pad * 2) * k,
        backgroundImage: `url("${url}")`,
        backgroundSize: `${pageWidth * k}px ${pageHeight * k}px`,
        backgroundPosition: `${-(left - pad) * k}px ${-(top - pad) * k}px`,
      }}
    />
  )
}
