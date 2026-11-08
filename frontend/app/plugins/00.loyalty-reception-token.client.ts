import { captureLoyaltyReceptionToken } from '~/utils/loyaltyReceptionToken'
export default defineNuxtPlugin({
  name: 'loyalty-reception-token',
  enforce: 'pre',
  setup() {
    captureLoyaltyReceptionToken(window.location, window.history)
  },
})
