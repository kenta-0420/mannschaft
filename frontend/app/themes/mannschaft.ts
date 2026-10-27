import Aura from '@primeuix/themes/aura'

/** PrimeVueのSSRとCSRが同じテーマとdark selectorを使う。 */
export default {
  preset: Aura,
  options: {
    prefix: 'p',
    darkModeSelector: '.p-dark',
    cssLayer: false,
  },
}
