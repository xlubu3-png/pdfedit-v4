// @vitest-environment jsdom
import { cleanup, fireEvent, render } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AddedImage, AddedText, PageText, TextRun } from '../types'
import { AddedBox, ImageBox, RunBox } from './TextBoxes'

afterEach(cleanup)

const run: TextRun = {
  index: 0,
  text: '시행규칙',
  currentText: '시행규칙',
  edited: false,
  x: 72,
  y: 700,
  width: 80,
  height: 12,
  fontSize: 12,
  boxBottom: 697,
  boxTop: 713,
  fontFamily: '맑은 고딕',
  bold: false,
  sourceFont: 'Helvetica',
  color: '#000000',
  glyphReuse: true,
  ownChars: '',
  sourceFontMissing: false,
  originalRemoved: false,
  matchSimilarity: null,
  editFontFamily: null,
  editFontSize: null,
  editBold: null,
  editColor: null,
  editSpans: [],
  dx: 0,
  dy: 0,
}

const data: PageText = {
  pageWidth: 595,
  pageHeight: 842,
  originX: 0,
  originY: 0,
  rotated: false,
  runs: [run],
  added: [],
  images: [],
}

const targets = { xs: [], ys: [] }
const noop = () => {}

function renderRun(editing: boolean, onCommitText = vi.fn(), onStopEdit = vi.fn()) {
  const props = {
    run,
    state: undefined,
    scale: 1.5,
    data,
    selected: true,
    targets,
    onSelect: noop,
    onStartEdit: noop,
    onStopEdit,
    onCommitText,
    onMoveTo: noop,
    onGuides: noop,
    onSelection: noop,
  }
  const view = render(<RunBox {...props} editing={editing} />)
  const box = () => view.container.querySelector<HTMLElement>('[data-index]')!
  return { view, box, rerender: (next: boolean) => view.rerender(<RunBox {...props} editing={next} />) }
}

describe('RunBox', () => {
  it('keeps the typed text when it is left in the usual way (focus leaves while editing)', () => {
    const onCommitText = vi.fn()
    const { box } = renderRun(true, onCommitText)

    box().textContent = '시행규칙칙'
    fireEvent.blur(box())

    expect(onCommitText).toHaveBeenCalledWith(run, '시행규칙칙')
  })

  it('keeps the typed text when a click on the page turns editing off before the focus leaves', () => {
    // Clicking an empty part of the page switches "editing" off first; the browser's blur comes after.
    // That blur used to be ignored, so the edit was lost until the box was entered and left again.
    const onCommitText = vi.fn()
    const onStopEdit = vi.fn()
    const { box, rerender } = renderRun(true, onCommitText, onStopEdit)

    box().textContent = '시행규칙칙'
    rerender(false)
    fireEvent.blur(box())

    expect(onCommitText).toHaveBeenCalledWith(run, '시행규칙칙')
  })

  it('is not editable until editing starts', () => {
    const { box, rerender } = renderRun(false)

    expect(box().getAttribute('contenteditable')).toBe('false')
    rerender(true)
    expect(box().getAttribute('contenteditable')).toBe('true')
  })
})

const added: AddedText = { id: 'a1', text: '새 글자', x: 100, y: 400, fontFamily: null, fontSize: 12, bold: false, color: '#000000', spans: [] }

describe('AddedBox', () => {
  it('keeps the typed text when editing is switched off before the focus leaves', () => {
    const onCommitText = vi.fn()
    const props = {
      box: added,
      scale: 1.5,
      data,
      selected: true,
      targets,
      onSelect: noop,
      onStartEdit: noop,
      onStopEdit: noop,
      onCommitText,
      onMoveTo: noop,
      onRemove: noop,
      onGuides: noop,
      onSelection: noop,
    }
    const view = render(<AddedBox {...props} editing={true} />)
    const box = view.container.querySelector<HTMLElement>('[data-added]')!

    box.textContent = '새 글자 더'
    view.rerender(<AddedBox {...props} editing={false} />)
    fireEvent.blur(box)

    expect(onCommitText).toHaveBeenCalledWith(added, '새 글자 더', null)
  })

  it('shows the parts with a look of their own in that look, the text itself unchanged', () => {
    const formatted: AddedText = {
      ...added,
      text: 'Hello World',
      spans: [{ start: 6, end: 11, bold: true, color: '#ff0000', size: 24, underline: true }],
    }
    const view = render(
      <AddedBox
        box={formatted}
        scale={1.5}
        data={data}
        selected={false}
        editing={false}
        targets={targets}
        onSelect={noop}
        onStartEdit={noop}
        onStopEdit={noop}
        onCommitText={noop}
        onMoveTo={noop}
        onRemove={noop}
        onGuides={noop}
        onSelection={noop}
      />,
    )
    const box = view.container.querySelector<HTMLElement>('[data-added]')!
    const part = box.querySelector<HTMLElement>('span')!

    expect(box.textContent).toBe('Hello World')
    expect(part.textContent).toBe('World')
    expect(part.style.fontWeight).toBe('bold')
    expect(part.style.color).toBe('rgb(255, 0, 0)')
    expect(part.style.fontSize).toBe('2em') // 24pt on a 12pt box
    expect(part.style.textDecoration).toContain('underline')
  })
})

describe('ImageBox', () => {
  const box: AddedImage = { id: 'i1', imageId: 'p1', x: 100, y: 600, width: 80, height: 40, keyOutWhite: false }

  function renderImage(overrides: Partial<Parameters<typeof ImageBox>[0]> = {}) {
    const props = {
      box,
      documentId: 'd1',
      scale: 2,
      data,
      selected: true,
      onSelect: vi.fn(),
      onMoveTo: vi.fn(),
      onResize: vi.fn(),
      onRemove: vi.fn(),
      ...overrides,
    }
    const view = render(<ImageBox {...props} />)
    return { props, view, frame: () => view.container.querySelector<HTMLElement>('.te-image')! }
  }

  it('frames the picture at its place and size on the page', () => {
    const { frame, view } = renderImage()

    expect(frame().style.width).toBe('160px') // 80pt at 2 px per point
    expect(frame().style.height).toBe('80px')
    const item = view.container.querySelector<HTMLElement>('.te-item')!
    expect(item.style.left).toBe('200px')
    // the top edge is at y = 640 of a 842pt page
    expect(item.style.top).toBe(`${(842 - 640) * 2}px`)
    expect(view.container.querySelector('img')!.getAttribute('src')).toBe('/api/v1/pdf/documents/d1/images/p1')
  })

  it('removes the picture from its ✕', () => {
    const { props, view } = renderImage()

    fireEvent.click(view.getByLabelText('그림 삭제'))

    expect(props.onRemove).toHaveBeenCalledWith(box)
  })

  it('resizes by the corner keeping the proportions and the top left corner', () => {
    const { props, view } = renderImage()
    const corner = view.container.querySelector<HTMLElement>('.te-resize')!
    corner.setPointerCapture = vi.fn()

    fireEvent.pointerDown(corner, { button: 0, pointerId: 1, clientX: 100 })
    fireEvent.pointerUp(corner, { pointerId: 1, clientX: 140 }) // 40 px = 20 pt wider

    // the top edge stays at 640, so a 50pt tall picture has its bottom at 590
    expect(props.onResize).toHaveBeenCalledWith(box, 100, 590, 100, 50)
  })
})
