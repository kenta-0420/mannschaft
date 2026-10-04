// @vitest-environment happy-dom
import { createI18n } from 'vue-i18n'
import jaMessages from '~/locales/ja/ranch.json'
import enMessages from '~/locales/en/ranch.json'
import zhMessages from '~/locales/zh/ranch.json'
import koMessages from '~/locales/ko/ranch.json'
import esMessages from '~/locales/es/ranch.json'
import deMessages from '~/locales/de/ranch.json'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DinosaurScene from './DinosaurScene.vue'
import type { DinosaurSummary } from '~/types/ranch'
const dinosaur: DinosaurSummary={id:'test-id',speciesKey:'S01',variantKey:'V1',habitat:'SEA',speciesCatalogVersion:'dev',stage:'BABY',name:'テスト',namedAt:null,xp:'9223372036854775807',nextStageXp:null,version:'1',egg:null}
let observerCallback: IntersectionObserverCallback; let reduced = false; let changeMotion: (() => void) | undefined
const pending = new Map<number,FrameRequestCallback>(); let sequence=0
beforeEach(() => {
 vi.stubGlobal('IntersectionObserver', class { constructor(callback: IntersectionObserverCallback){observerCallback=callback} observe(){} unobserve(){} disconnect(){} })
 vi.stubGlobal('requestAnimationFrame', (callback:FrameRequestCallback) => {const id=++sequence; pending.set(id,callback);return id})
 vi.stubGlobal('cancelAnimationFrame', (id:number) => pending.delete(id))
 vi.stubGlobal('matchMedia', () => ({get matches(){return reduced},addEventListener(_event:string,callback:()=>void){changeMotion=callback},removeEventListener(){}}))
 Object.defineProperty(document,'hidden',{configurable:true,value:false}); pending.clear(); reduced=false
})
afterEach(() => {vi.unstubAllGlobals();pending.clear()})
async function show(){ observerCallback([{isIntersecting:true}] as IntersectionObserverEntry[], {} as IntersectionObserver); await nextTick() }
describe('AC59/71 scene資源の停止', () => {
 it('初回active前はassetを取得せず、画面外→可視で同個体を表示する', async () => {
  const wrapper=mount(DinosaurScene,{global:{plugins:[createI18n({legacy:false,locale:'ja',messages:{ja:jaMessages,en:enMessages,zh:zhMessages,ko:koMessages,es:esMessages,de:deMessages}})]},props:{dinosaur,renderStyle:'PIXEL',motionMode:'NORMAL',active:false,asset:{dinosaurId:dinosaur.id,speciesKey:'S01',variantKey:'V1',stage:'BABY',renderStyle:'PIXEL',staticUrl:'/test-only.png',approved:true}}})
  await show(); expect(wrapper.find('img').exists()).toBe(false); expect(pending.size).toBe(0)
  await wrapper.setProps({active:true}); expect(wrapper.find('img').attributes('src')).toBe('/test-only.png'); expect(wrapper.attributes('data-dinosaur-id')).toBe(dinosaur.id); expect(pending.size).toBe(1)
  await wrapper.setProps({active:false}); expect(pending.size).toBe(0);wrapper.unmount()
 })
 it('REDUCED/STOPPED/OSreduceとhiddenタブは背景RAFを停止し復帰後追いつかない', async () => {
  const wrapper=mount(DinosaurScene,{global:{plugins:[createI18n({legacy:false,locale:'ja',messages:{ja:jaMessages,en:enMessages,zh:zhMessages,ko:koMessages,es:esMessages,de:deMessages}})]},props:{dinosaur,renderStyle:'PIXEL',motionMode:'NORMAL'}});await show();expect(pending.size).toBe(1)
  await wrapper.setProps({motionMode:'REDUCED'});expect(pending.size).toBe(0)
  await wrapper.setProps({motionMode:'STOPPED'});expect(pending.size).toBe(0)
  await wrapper.setProps({motionMode:'NORMAL'});expect(pending.size).toBe(1)
  reduced=true;changeMotion?.();await nextTick();expect(pending.size).toBe(0)
  reduced=false;changeMotion?.();await nextTick();expect(pending.size).toBe(1)
  Object.defineProperty(document,'hidden',{configurable:true,value:true});document.dispatchEvent(new Event('visibilitychange'));await nextTick();expect(pending.size).toBe(0)
  Object.defineProperty(document,'hidden',{configurable:true,value:false});document.dispatchEvent(new Event('visibilitychange'));await nextTick();expect(pending.size).toBe(1)
  const [id,callback]=[...pending.entries()][0]!;pending.delete(id);callback(1000000);await nextTick();expect(wrapper.find('span').attributes('style')).toContain('bottom: 13%')
  wrapper.unmount();expect(pending.size).toBe(0)
 })
 it('素材の取得失敗は文字fallbackへ戻し個体・styleを変更しない', async () => {
  const wrapper=mount(DinosaurScene,{global:{plugins:[createI18n({legacy:false,locale:'ja',messages:{ja:jaMessages,en:enMessages,zh:zhMessages,ko:koMessages,es:esMessages,de:deMessages}})]},props:{dinosaur,renderStyle:'PIXEL',motionMode:'STOPPED',asset:{dinosaurId:dinosaur.id,speciesKey:'S01',variantKey:'V1',stage:'BABY',renderStyle:'PIXEL',staticUrl:'/missing.png',approved:true}}});await show();await wrapper.find('img').trigger('error');expect(wrapper.find('img').exists()).toBe(false);expect(wrapper.attributes('data-dinosaur-id')).toBe(dinosaur.id);expect(wrapper.text()).toContain('テスト');wrapper.unmount()
 })
})
