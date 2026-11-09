import { useEffect, useRef, useState } from 'react'
import { addStamp, deleteStamp, fetchStamps, stampImageUrl } from '../api'
import type { Stamp } from '../api'
import { errorText } from '../errors'

interface StampPanelProps {
  /** Stamps it where the user clicks next. */
  onPlace: (stamp: Stamp) => void
  /** Stamps it where it went last. */
  onPlaceLast: (stamp: Stamp) => void
  /** The picked picture came from this stamp: its size and place can be kept as the stamp's. */
  pickedFrom: Stamp | null
  onRemember: (stamp: Stamp) => void
  onClose: () => void
}

/** 도장·서명 찍기: the registered seals and signatures, to stamp onto the page; new ones are registered here. */
export function StampPanel({ onPlace, onPlaceLast, pickedFrom, onRemember, onClose }: StampPanelProps) {
  const [stamps, setStamps] = useState<Stamp[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const input = useRef<HTMLInputElement>(null)

  useEffect(() => {
    fetchStamps()
      .then(setStamps)
      .catch((e) => setError(errorText(e, '도장 목록을 불러오지 못했습니다')))
  }, [])

  async function register(file: File) {
    const name = window.prompt('도장·서명 이름 (예: 회사 직인, 대표 서명)', file.name.replace(/\.[^.]+$/, ''))
    if (name === null) return
    setBusy(true)
    setError(null)
    try {
      const added = await addStamp(file, name)
      setStamps((prev) => [...(prev ?? []), added])
    } catch (e) {
      setError(errorText(e, '도장을 등록하지 못했습니다'))
    } finally {
      setBusy(false)
    }
  }

  async function remove(stamp: Stamp) {
    if (!window.confirm(`'${stamp.name}'을(를) 목록에서 지울까요? (이미 찍은 문서는 그대로입니다)`)) return
    try {
      await deleteStamp(stamp.id)
      setStamps((prev) => (prev ?? []).filter((s) => s.id !== stamp.id))
    } catch (e) {
      setError(errorText(e, '도장을 지우지 못했습니다'))
    }
  }

  return (
    <div className="te-stamps" role="dialog" aria-label="도장·서명 찍기">
      <div className="te-stamps-head">
        <b>도장·서명 찍기</b>
        <button type="button" onClick={onClose} aria-label="닫기">
          ✕
        </button>
      </div>
      <p className="te-stamps-hint">고른 뒤 쪽에서 찍을 곳을 누르세요. 흰 바탕은 투명하게 찍히고, 찍은 뒤 끌어 옮기거나 크기를 바꿀 수 있습니다.</p>
      {stamps === null && !error && <p>불러오는 중…</p>}
      {stamps?.length === 0 && <p>등록된 도장·서명이 없습니다. 아래에서 그림(PNG·JPG)을 등록하세요.</p>}
      <ul>
        {stamps?.map((stamp) => (
          <li key={stamp.id}>
            <img src={stampImageUrl(stamp.id)} alt="" />
            <span className="te-stamps-name">{stamp.name}</span>
            <button type="button" className="primary" onClick={() => onPlace(stamp)}>
              찍기
            </button>
            {stamp.lastX !== null && (
              <button type="button" onClick={() => onPlaceLast(stamp)} title="지난번에 찍은 자리·크기로 바로 찍습니다">
                지난 자리에
              </button>
            )}
            {pickedFrom?.id === stamp.id && (
              <button type="button" onClick={() => onRemember(stamp)} title="고른 그림의 크기와 자리를 이 도장의 기본으로 기억합니다">
                지금 크기·자리 기억
              </button>
            )}
            <button type="button" onClick={() => void remove(stamp)} aria-label={`${stamp.name} 지우기`}>
              지우기
            </button>
          </li>
        ))}
      </ul>
      <button type="button" onClick={() => input.current?.click()} disabled={busy}>
        + 새 도장·서명 등록…
      </button>
      <input
        ref={input}
        type="file"
        accept="image/png,image/jpeg"
        hidden
        onChange={(e) => {
          const file = e.target.files?.[0]
          e.target.value = ''
          if (file) void register(file)
        }}
      />
      {error && <p className="find-message error">{error}</p>}
    </div>
  )
}
