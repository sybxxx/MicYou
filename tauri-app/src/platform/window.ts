import type { UnlistenFn } from '@tauri-apps/api/event';
import { getCurrentWebviewWindow } from '@tauri-apps/api/webviewWindow';
import { getCurrentWindow, LogicalSize } from '@tauri-apps/api/window';

/** Operations on the window this webview lives in. */

export function closeCurrentWindow(): Promise<void> {
  return getCurrentWindow().close();
}

/** Closes without emitting a close request, for windows that intercept it. */
export function destroyCurrentWindow(): Promise<void> {
  return getCurrentWindow().destroy();
}

export function hideCurrentWindow(): Promise<void> {
  return getCurrentWindow().hide();
}

export function startDragging(): Promise<void> {
  return getCurrentWindow().startDragging();
}

export function isMinimized(): Promise<boolean> {
  return getCurrentWindow().isMinimized();
}

export function isFocused(): Promise<boolean> {
  return getCurrentWindow().isFocused();
}

export function isVisible(): Promise<boolean> {
  return getCurrentWindow().isVisible();
}

/** Draws attention to a pairing request without changing the close policy. */
export async function requestPairingAttention(): Promise<void> {
  const win = getCurrentWindow();
  await win.setFocus();
  await win.requestUserAttention(1);
}

export function onFocusChanged(handler: (focused: boolean) => void): Promise<UnlistenFn> {
  return getCurrentWindow().onFocusChanged(({ payload }) => handler(payload));
}

export function onResized(handler: () => void): Promise<UnlistenFn> {
  return getCurrentWindow().onResized(handler);
}

/**
 * Intercepts compositor close requests (Alt+F4, taskbar, window menu); the
 * window stays open and the handler decides what to do.
 */
export function onCloseRequested(handler: () => void): Promise<UnlistenFn> {
  return getCurrentWindow().onCloseRequested((event) => {
    event.preventDefault();
    handler();
  });
}

/** Resizes to a fixed logical size; min = max keeps the user from resizing it. */
export async function setFixedSize(width: number, height: number): Promise<void> {
  const win = getCurrentWindow();
  const size = new LogicalSize(width, height);
  await win.setMinSize(null);
  await win.setMaxSize(null);
  await win.setSize(size);
  await win.setMinSize(size);
  await win.setMaxSize(size);
}

export async function logicalSize(): Promise<{ width: number; height: number }> {
  const win = getCurrentWindow();
  const [size, scale] = await Promise.all([win.innerSize(), win.scaleFactor()]);
  return { width: size.width / scale, height: size.height / scale };
}

/** Full mode stays resizable; pocket mode can temporarily pin its bounds. */
export async function setResizableSize(width: number, height: number): Promise<void> {
  const win = getCurrentWindow();
  await win.setMinSize(null);
  await win.setMaxSize(null);
  await win.setResizable(true);
  await win.setSize(new LogicalSize(Math.max(width, 720), Math.max(height, 560)));
  await win.setMinSize(new LogicalSize(720, 560));
}

export type FileDropEvent =
  | { type: 'enter' }
  | { type: 'over' }
  | { type: 'leave' }
  | { type: 'drop'; paths: string[] };

export function onFileDrop(handler: (event: FileDropEvent) => void): Promise<UnlistenFn> {
  return getCurrentWebviewWindow().onDragDropEvent(({ payload }) => {
    handler(payload.type === 'drop' ? { type: 'drop', paths: payload.paths } : { type: payload.type });
  });
}
