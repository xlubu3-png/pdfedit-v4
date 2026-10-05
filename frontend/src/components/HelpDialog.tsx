import { HELP_BASICS, SHORTCUT_GROUPS } from '../shortcuts'

interface HelpDialogProps {
  onClose: () => void
}

/** How the editor is used and every keyboard shortcut, laid out as a table instead of one long paragraph. */
export function HelpDialog({ onClose }: HelpDialogProps) {
  return (
    <div className="find-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="find-dialog help-dialog" role="dialog" aria-modal="true" aria-label="도움말과 단축키">
        <div className="find-head">
          <b>도움말과 단축키</b>
          <button type="button" onClick={onClose} aria-label="닫기">
            ✕
          </button>
        </div>

        <section>
          <h3>사용 방법</h3>
          <ol className="help-basics">
            {HELP_BASICS.map((step) => (
              <li key={step}>{step}</li>
            ))}
          </ol>
        </section>

        <section>
          <h3>단축키</h3>
          <div className="help-groups">
            {SHORTCUT_GROUPS.map((group) => (
              <div key={group.title} className="help-group">
                <h4>{group.title}</h4>
                <dl>
                  {group.items.map((item) => (
                    <div key={item.keys.join('+')} className="help-row">
                      <dt>
                        {item.keys.map((key, i) => (
                          <span key={key}>
                            {i > 0 && <span className="help-plus">+</span>}
                            <kbd>{key}</kbd>
                          </span>
                        ))}
                      </dt>
                      <dd>{item.label}</dd>
                    </div>
                  ))}
                </dl>
              </div>
            ))}
          </div>
        </section>

        <div className="find-actions">
          <button type="button" className="primary" onClick={onClose}>
            닫기
          </button>
        </div>
      </div>
    </div>
  )
}
