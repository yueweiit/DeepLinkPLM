import { afterEach, describe, expect, it, vi } from 'vitest'
import { createAuthorizedBlobQueue } from '../authorizedBlobQueue'

function deferred<T>() { let resolve!: (value: T) => void, reject!: (cause: unknown) => void; const promise = new Promise<T>((yes,no) => { resolve=yes; reject=no }); return {promise,resolve,reject} }
const photo = (value='image') => new Blob([value], {type:'image/jpeg'})
const settle = async () => { for(let n=0;n<8;n++) await Promise.resolve() }
afterEach(() => vi.restoreAllMocks())

describe('bounded authorised preview queue', () => {
 it('limits actual parallel downloads and deduplicates two viewers without cancelling the survivor', async () => {
  const queue=createAuthorizedBlobQueue({concurrency:2}), a=deferred<Blob>(), b=deferred<Blob>(), c=deferred<Blob>()
  const loadA=vi.fn(()=>a.promise), loadB=vi.fn(()=>b.promise), loadC=vi.fn(()=>c.promise)
  const first=queue.acquire('a',loadA), second=queue.acquire('a',loadA), other=queue.acquire('b',loadB), waiting=queue.acquire('c',loadC)
  await settle();expect(loadA).toHaveBeenCalledTimes(1);expect(loadB).toHaveBeenCalledTimes(1);expect(loadC).not.toHaveBeenCalled()
  first.release()
  a.resolve(photo('a'));await expect(second.promise).resolves.toHaveProperty('type','image/jpeg');await settle();expect(loadC).toHaveBeenCalledTimes(1)
  b.resolve(photo('b'));c.resolve(photo('c'));await Promise.all([other.promise,waiting.promise]);second.release();other.release();waiting.release()
 })
 it('does not abort a shared request until its last consumer releases', async () => {
  const queue=createAuthorizedBlobQueue(), result=deferred<Blob>();let signal!:AbortSignal
  const fetch=vi.fn((value:AbortSignal)=>{signal=value;return result.promise})
  const a=queue.acquire('same',fetch), b=queue.acquire('same',fetch);const rejected=a.promise.catch(error=>error)
  await settle();a.release();expect(signal.aborted).toBe(false);b.release();expect(signal.aborted).toBe(true)
  result.resolve(photo());expect((await rejected).name).toBe('AbortError')
 })
 it('removes unused queued work and starts interactive previews before queued thumbnails', async () => {
  const queue=createAuthorizedBlobQueue({concurrency:1}), a=deferred<Blob>(), order:string[]=[]
  const active=queue.acquire('active',()=>{order.push('active');return a.promise})
  const droppedFetch=vi.fn(async()=>photo()), dropped=queue.acquire('unused',droppedFetch), droppedResult=dropped.promise.catch(error=>error)
  const thumb=queue.acquire('thumbnail',async()=>{order.push('thumbnail');return photo()})
  const clicked=queue.acquire('clicked',async()=>{order.push('clicked');return photo()},true)
  dropped.release();await settle();a.resolve(photo());await Promise.all([active.promise,thumb.promise,clicked.promise]);await settle()
  expect(order).toEqual(['active','clicked','thumbnail']);expect(droppedFetch).not.toHaveBeenCalled();expect((await droppedResult).name).toBe('AbortError')
 })
 it('caches successful Blobs but evicts by bytes, entries and TTL rather than retaining unlimited original images', async () => {
  let now=10;vi.spyOn(Date,'now').mockImplementation(()=>now)
  const queue=createAuthorizedBlobQueue({maxBytes:6,maxEntries:2,ttl:50}), fetch=vi.fn(async()=>photo('abc'))
  await queue.acquire('a',fetch).promise;await settle();await queue.acquire('a',fetch).promise;expect(fetch).toHaveBeenCalledTimes(1)
  await queue.acquire('b',fetch).promise;await settle();await queue.acquire('c',fetch).promise;await settle()
  await queue.acquire('a',fetch).promise;await settle();expect(fetch).toHaveBeenCalledTimes(4)
  now+=60;await queue.acquire('a',fetch).promise;await settle();expect(fetch).toHaveBeenCalledTimes(5)
  const tooLarge=vi.fn(async()=>photo('1234567'));await queue.acquire('large',tooLarge).promise;await settle();await queue.acquire('large',tooLarge).promise;expect(tooLarge).toHaveBeenCalledTimes(2)
 })
 it('does not cache a failed response and lets manual retry load again', async () => {
  const queue=createAuthorizedBlobQueue(), fetch=vi.fn().mockRejectedValueOnce(new Error('temporary')).mockResolvedValueOnce(photo())
  await expect(queue.acquire('a',fetch).promise).rejects.toThrow('temporary');await settle()
  await expect(queue.acquire('a',fetch).promise).resolves.toHaveProperty('type','image/jpeg');expect(fetch).toHaveBeenCalledTimes(2)
 })
 it('does not start old queued work under a replacement login and rejects old late responses', async () => {
  let scope='session-one';const queue=createAuthorizedBlobQueue({concurrency:1,scope:()=>scope}), old=deferred<Blob>()
  const first=queue.acquire('active',()=>old.promise), queuedFetch=vi.fn(async()=>photo()), queued=queue.acquire('queued',queuedFetch)
  const oldResult=first.promise.catch(error=>error), queuedResult=queued.promise.catch(error=>error)
  await settle();scope='session-two';old.resolve(photo('private-old'));await settle()
  expect((await oldResult).name).toBe('AbortError');expect((await queuedResult).name).toBe('AbortError');expect(queuedFetch).not.toHaveBeenCalled()
  const currentFetch=vi.fn(async()=>photo('current'));await queue.acquire('active',currentFetch).promise;expect(currentFetch).toHaveBeenCalledTimes(1)
 })
 it('checks session again immediately before an asynchronous fetch starts', async () => {
  let scope='session-one';const queue=createAuthorizedBlobQueue({scope:()=>scope}), fetch=vi.fn(async()=>photo())
  const current=queue.acquire('queued-in-microtask',fetch), failed=current.promise.catch(error=>error)
  scope='session-two';await settle();expect(fetch).not.toHaveBeenCalled();expect((await failed).name).toBe('AbortError')
 })
 it('never reuses a completed cached image across login identities', async () => {
  let scope='session-one';const queue=createAuthorizedBlobQueue({scope:()=>scope}), fetch=vi.fn(async()=>photo())
  await queue.acquire('a',fetch).promise;await settle();scope='session-two';await queue.acquire('a',fetch).promise
  expect(fetch).toHaveBeenCalledTimes(2)
 })
})
