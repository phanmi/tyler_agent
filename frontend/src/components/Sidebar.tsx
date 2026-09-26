// Switch between chat and settings using React state.
export type View = 'chat' | 'settings'

interface SidebarProps {
  current: View
  onNavigate: (view: View) => void
}

interface NavItemDef {
  id: View
  label: string
  icon: string
}

const NAV_ITEMS: NavItemDef[] = [
  { id: 'chat', label: 'Chat', icon: '💬' },
  { id: 'settings', label: 'Settings', icon: '⚙️' },
]

// Render navigation items and highlight the active view.
// App owns navigation state; clicks call onNavigate.
export default function Sidebar({ current, onNavigate }: SidebarProps) {
  return (
    <nav className="sidebar">
      <span className="sidebar__label">Navigation</span>
      {NAV_ITEMS.map((item) => (
        <button
          key={item.id}
          type="button"
          className={`nav-item ${current === item.id ? 'nav-item--active' : ''}`}
          onClick={() => onNavigate(item.id)}
        >
          <span className="nav-item__icon">{item.icon}</span>
          {item.label}
        </button>
      ))}
    </nav>
  )
}
