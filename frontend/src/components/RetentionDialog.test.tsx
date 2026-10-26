// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { RetentionDialog } from './RetentionDialog'

afterEach(cleanup)

describe('RetentionDialog', () => {
  it('saves the period chosen and closes', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined)
    const onClose = vi.fn()
    render(<RetentionDialog days={30} onSave={onSave} onClose={onClose} />)
    const save = screen.getByText('저장') as HTMLButtonElement
    expect(save.disabled).toBe(true)

    fireEvent.change(screen.getByLabelText(/보관할 기간/), { target: { value: '90' } })
    fireEvent.click(save)

    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(onSave).toHaveBeenCalledWith(90)
  })

  it('warns that a shorter period cleans up older documents soon', () => {
    render(<RetentionDialog days={30} onSave={vi.fn()} onClose={vi.fn()} />)

    fireEvent.change(screen.getByLabelText(/보관할 기간/), { target: { value: '7' } })

    expect(screen.getByText(/7일 넘게 열지 않은 문서는 몇 분 안에 정리됩니다/)).toBeTruthy()
  })

  it('offers a period set elsewhere and stays open when saving fails', async () => {
    const onClose = vi.fn()
    render(<RetentionDialog days={45} onSave={vi.fn().mockRejectedValue(new Error('서버 오류'))} onClose={onClose} />)
    expect(screen.getByRole('option', { name: '45일' })).toBeTruthy()

    fireEvent.change(screen.getByLabelText(/보관할 기간/), { target: { value: '365' } })
    fireEvent.click(screen.getByText('저장'))

    await screen.findByText('서버 오류')
    expect(onClose).not.toHaveBeenCalled()
  })
})
