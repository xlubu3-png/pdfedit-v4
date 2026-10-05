/** One keyboard shortcut for the help window: the keys to press and what they do. */
export interface Shortcut {
  keys: string[]
  label: string
}

export interface ShortcutGroup {
  title: string
  items: Shortcut[]
}

/** How the editor is used, in the order a new user meets it. */
export const HELP_BASICS: string[] = [
  '줄을 한 번 클릭하면 선택됩니다. 선택하면 위쪽 막대에서 글꼴·크기·굵기·색을 바꿀 수 있습니다.',
  '한 번 더 클릭하거나, 더블클릭, 또는 Enter를 누르면 글자를 고칠 수 있습니다. 다 고쳤으면 다른 곳을 클릭하세요.',
  '상자나 왼쪽의 ✥ 손잡이를 끌면 줄을 옮길 수 있고, 다른 줄의 왼쪽 끝·기준선에 정렬선이 붙습니다.',
  '‘＋ 텍스트 추가’를 누른 뒤 페이지의 원하는 곳을 클릭하면 새 글자를 넣을 수 있습니다.',
  '수정한 부분은 원래 배경색으로 덮은 뒤 다시 그리는 방식이라, 원본 글꼴이 완벽히 재현되지 않을 수 있습니다.',
]

export const SHORTCUT_GROUPS: ShortcutGroup[] = [
  {
    title: '글자 고치기',
    items: [
      { keys: ['Enter'], label: '선택한 줄의 글자 수정 (F2도 같음)' },
      { keys: ['Esc'], label: '한 단계씩 빠져나오기: 입력 → 선택 → 편집 화면 닫기' },
      { keys: ['T'], label: '텍스트 추가 (누른 뒤 페이지를 클릭)' },
      { keys: ['Delete'], label: '선택한 추가 텍스트 삭제' },
    ],
  },
  {
    title: '모양 바꾸기',
    items: [
      { keys: ['Ctrl', 'B'], label: '굵게' },
      { keys: ['Ctrl', ']'], label: '글자 크기 1pt 키우기' },
      { keys: ['Ctrl', '['], label: '글자 크기 1pt 줄이기' },
    ],
  },
  {
    title: '옮기기',
    items: [
      { keys: ['방향키'], label: '선택한 줄을 1pt씩 이동' },
      { keys: ['Shift', '방향키'], label: '10pt씩 이동' },
      { keys: ['Alt', '방향키'], label: '글자를 입력하는 중에도 이동' },
    ],
  },
  {
    title: '편집',
    items: [
      { keys: ['Ctrl', 'Z'], label: '실행 취소' },
      { keys: ['Ctrl', 'Y'], label: '다시 실행 (Ctrl+Shift+Z도 같음)' },
      { keys: ['Ctrl', 'C'], label: '선택한 줄 복사' },
      { keys: ['Ctrl', 'V'], label: '붙여넣기 (다른 페이지에도 가능)' },
      { keys: ['Ctrl', 'D'], label: '선택한 줄 복제' },
    ],
  },
  {
    title: '보기와 이동',
    items: [
      { keys: ['Ctrl', '+'], label: '확대' },
      { keys: ['Ctrl', '-'], label: '축소' },
      { keys: ['Ctrl', '0'], label: '폭에 맞춤' },
      { keys: ['Ctrl', '←'], label: '이전 페이지' },
      { keys: ['Ctrl', '→'], label: '다음 페이지' },
    ],
  },
  {
    title: '파일',
    items: [
      { keys: ['Ctrl', 'S'], label: 'PDF로 내보내기' },
      { keys: ['Ctrl', 'Shift', 'S'], label: '수정 내용 반영' },
      { keys: ['Ctrl', 'F'], label: '찾아 바꾸기' },
      { keys: ['?'], label: '이 도움말 열기' },
    ],
  },
]
