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
const dinosaur: DinosaurSummary={id:'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',speciesKey:'S01',variantKey:'V1',habitat:'SEA',speciesCatalogVersion:'dev',stage:'BABY',name:'テスト',namedAt:null,xp:'9223372036854775807',nextStageXp:null,version:'1',egg:null}
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
 it('初回inactiveは取得0、有限manifestにない素材は可視後もfallback', async () => {
  const i18n=createI18n({legacy:false,locale:'ja',messages:{ja:jaMessages,en:enMessages,zh:zhMessages,ko:koMessages,es:esMessages,de:deMessages}})
  const wrapper=mount(DinosaurScene,{global:{plugins:[i18n]},props:{dinosaur,renderStyle:'PIXEL',motionMode:'NORMAL',active:false}})
  await show(); expect(wrapper.find('img').exists()).toBe(false); expect(pending.size).toBe(0)
  await wrapper.setProps({active:true}); expect(wrapper.find('canvas').exists()).toBe(false); expect(wrapper.text()).toContain(i18n.global.t('ranch.scene.assetPreparing')); expect(wrapper.attributes('data-dinosaur-id')).toBe(dinosaur.id); expect(pending.size).toBe(1)
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
 it('未承認catalogは両styleともfallbackで同個体を維持する', async () => {
  const wrapper=mount(DinosaurScene,{global:{plugins:[createI18n({legacy:false,locale:'ja',messages:{ja:jaMessages,en:enMessages,zh:zhMessages,ko:koMessages,es:esMessages,de:deMessages}})]},props:{dinosaur,renderStyle:'PIXEL',motionMode:'STOPPED'}});await show();await wrapper.setProps({renderStyle:'PAINT_2D'});expect(wrapper.find('img').exists()).toBe(false);expect(wrapper.attributes('data-dinosaur-id')).toBe(dinosaur.id);expect(wrapper.text()).toContain('テスト');wrapper.unmount()
 })
 it('PAUSEDは背景だけ停止し保存済み本人TOUCHを有限終了、他個体とSTOPPEDは拒否する', async () => {
  const wrapper=mount(DinosaurScene,{global:{plugins:[createI18n({legacy:false,locale:'ja',messages:{ja:jaMessages,en:enMessages,zh:zhMessages,ko:koMessages,es:esMessages,de:deMessages}})]},props:{dinosaur,renderStyle:'PIXEL',motionMode:'NORMAL',backgroundPaused:true}})
  await show(); expect(pending.size).toBe(0)
  await wrapper.setProps({reaction:{key:'DINOSAUR_TOUCH',dinosaurId:'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',sequence:1}}); expect(pending.size).toBe(0)
  await wrapper.setProps({reaction:{key:'DINOSAUR_TOUCH',dinosaurId:dinosaur.id,sequence:2}}); expect(pending.size).toBe(1)
  for(const time of [0,701]) { const item=[...pending.entries()][0]; if(!item) throw new Error('REACTION_FRAME_MISSING'); pending.delete(item[0]); item[1](time); await nextTick() }
  expect(pending.size).toBe(0); expect(wrapper.find('[data-reaction-key]').attributes('data-reaction-key')).toBe('')
  await wrapper.setProps({motionMode:'STOPPED',reaction:{key:'DINOSAUR_TOUCH',dinosaurId:dinosaur.id,sequence:3}}); expect(pending.size).toBe(0); wrapper.unmount()
 })
 it('REDUCEDの同キー再反応は旧DOM終了で消えず、非activeで即取消する', async () => {
  const wrapper=mount(DinosaurScene,{global:{plugins:[createI18n({legacy:false,locale:'ja',messages:{ja:jaMessages,en:enMessages,zh:zhMessages,ko:koMessages,es:esMessages,de:deMessages}})]},props:{dinosaur,renderStyle:'PIXEL',motionMode:'REDUCED'}})
  await show(); await wrapper.setProps({reaction:{key:'DINOSAUR_TOUCH',dinosaurId:dinosaur.id,sequence:1}})
  const old=wrapper.find('[data-reaction-key]').element
  await wrapper.setProps({reaction:{key:'DINOSAUR_TOUCH',dinosaurId:dinosaur.id,sequence:2}})
  old.dispatchEvent(new Event('animationend')); await nextTick()
  expect(wrapper.find('[data-reaction-key]').attributes('data-reaction-key')).toBe('DINOSAUR_TOUCH'); expect(pending.size).toBe(0)
  await wrapper.setProps({active:false}); expect(wrapper.find('[data-reaction-key]').attributes('data-reaction-key')).toBe(''); wrapper.unmount()
 })

})
