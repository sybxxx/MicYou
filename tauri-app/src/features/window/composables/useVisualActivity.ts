import { computed, onMounted, onUnmounted, ref, type Ref } from 'vue';
import { appWindow } from '@/platform';
import { useListeners } from '@/shared/lib/listeners';

/** Pause only visual work; the hidden webview still handles pairing and tray actions. */
export function useVisualActivity(hidden: Readonly<Ref<boolean>>) {
  const documentVisible = ref(document.visibilityState === 'visible');
  const nativeVisible = ref(true);
  const track = useListeners();
  let disposed = false;
  let revision = 0;

  async function refreshNativeVisibility() {
    if (disposed) return;
    const current = ++revision;
    try {
      const [visible, minimized] = await Promise.all([
        appWindow.isVisible(), appWindow.isMinimized(),
      ]);
      if (!disposed && current === revision) nativeVisible.value = visible && !minimized;
    } catch (e) {
      console.warn('Failed to read visualization window state:', e);
    }
  }

  function onVisibilityChange() {
    documentVisible.value = document.visibilityState === 'visible';
    void refreshNativeVisibility();
  }

  onMounted(() => {
    document.addEventListener('visibilitychange', onVisibilityChange);
    void Promise.all([
      track(appWindow.onFocusChanged(() => void refreshNativeVisibility())),
      track(appWindow.onResized(() => void refreshNativeVisibility())),
    ]).then(refreshNativeVisibility).catch((e) => {
      console.warn('Failed to listen for visualization window state:', e);
    });
  });
  onUnmounted(() => {
    disposed = true;
    revision++;
    document.removeEventListener('visibilitychange', onVisibilityChange);
  });

  return computed(() => !hidden.value && documentVisible.value && nativeVisible.value);
}
