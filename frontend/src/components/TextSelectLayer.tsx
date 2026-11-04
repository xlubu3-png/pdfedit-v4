import { Fragment, memo, useLayoutEffect, useMemo, useRef } from 'react'
import { readingOrder } from '../textSelect'
import type { SelectableLine } from '../textSelect'

interface TextSelectLayerProps {
  lines: SelectableLine[]
}

interface OcrLineTextProps {
  text: string
  vertical: boolean
  /** How thick the line is (its height, or its width when it runs down the page), pixels. */
  across: number
  /** How long the line is on the page (its width, or its height when it runs down the page), pixels. */
  length: number
}

/**
 * A recognized line's text in see-through letters inside its outline, stretched to the outline's length: a drag
 * from one outline over the others picks their text to copy, as in a PDF viewer, while a click still picks the line.
 * Measured again only when the line or the zoom changes: a scanned page has a hundred of these, and the editor draws
 * itself again on every save and every pick.
 */
export const OcrLineText = memo(function OcrLineText({ text, vertical, across, length }: OcrLineTextProps) {
  const ref = useRef<HTMLSpanElement>(null)
  useLayoutEffect(() => {
    const span = ref.current
    if (!span) return
    span.style.transform = ''
    const natural = vertical ? span.offsetHeight : span.offsetWidth
    if (natural > 0 && length > 0) span.style.transform = `${vertical ? 'scaleY' : 'scaleX'}(${length / natural})`
  }, [text, vertical, across, length])
  return (
    <>
      <span
        ref={ref}
        className={`te-ocr-text${vertical ? ' vertical' : ''}`}
        style={{ fontSize: across * 0.85, lineHeight: `${across}px` }}
      >
        {vertical ? text.replace(/\s+/g, '') : text}
      </span>
      {/* Each line copied on a line of its own. */}
      <br />
    </>
  )
})

/**
 * The page's text laid over its picture in see-through letters, as a PDF viewer does, so it can be picked with the
 * mouse across lines and copied with Ctrl+C: each line where it is on the page, stretched to the page's length of
 * it, in reading order with a line break after each.
 */
export function TextSelectLayer({ lines }: TextSelectLayerProps) {
  const ref = useRef<HTMLDivElement>(null)
  const ordered = useMemo(() => readingOrder(lines), [lines])

  // Each line stretched (or squeezed) to the length it has on the page, so a drag picks the letters it passes over.
  useLayoutEffect(() => {
    ref.current?.querySelectorAll<HTMLSpanElement>('span[data-line]').forEach((span) => {
      const turn = Number(span.dataset.turn ?? 0)
      const vertical = span.dataset.vertical === '1'
      span.style.transform = turn ? `rotate(${-turn}deg)` : ''
      const length = Number(span.dataset.length ?? 0)
      const natural = vertical ? span.offsetHeight : span.offsetWidth
      if (length > 0 && natural > 0) {
        const stretch = `${vertical ? 'scaleY' : 'scaleX'}(${length / natural})`
        span.style.transform = turn ? `rotate(${-turn}deg) ${stretch}` : stretch
      }
    })
  }, [ordered])

  return (
    <div className="te-textlayer" ref={ref} aria-label="쪽의 글자 (끌어서 고르고 Ctrl+C로 복사)">
      {ordered.map((line) => (
        <Fragment key={line.key}>
          <span
            data-line=""
            data-key={line.key}
            data-length={line.length ?? 0}
            data-turn={line.turn ?? 0}
            data-vertical={line.vertical ? '1' : '0'}
            style={{
              left: line.left,
              top: line.top,
              fontSize: line.size,
              lineHeight: `${line.vertical ? line.width : line.height}px`,
              ...(line.vertical ? { writingMode: 'vertical-rl' as const } : {}),
            }}
          >
            {line.text}
          </span>
          <br />
        </Fragment>
      ))}
    </div>
  )
}
