import { useStorage } from '@vueuse/core';

// The main window persists server.json. Storage events carry settings-window
// changes to that owner; startup always reloads the backend value.
export const requireEncryption = useStorage('micyou_require_encryption', false);
