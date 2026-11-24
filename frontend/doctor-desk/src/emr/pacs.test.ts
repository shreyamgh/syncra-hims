import { afterEach, describe, expect, it, vi } from 'vitest';
import { openImages, viewerUrl } from './pacs';

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('image viewer', () => {
  it('opens the tab inside the click, sets the viewer session, then shows the study', async () => {
    const tab = { opener: {} as unknown, location: { href: 'about:blank' }, close: vi.fn() };
    const open = vi.spyOn(window, 'open').mockReturnValue(tab as unknown as Window);
    const fetch = vi.fn(async () => new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetch);

    await openImages('1.2.840.113619.2.55');
    expect(open).toHaveBeenCalledWith('about:blank', '_blank');
    // The session response's cookie must be kept, so this call (alone) sends same-origin credentials.
    expect(fetch).toHaveBeenCalledWith('/pacs/session', expect.objectContaining({ method: 'POST', credentials: 'same-origin' }));
    expect(tab.location.href).toBe('/pacs/ohif/viewer?StudyInstanceUIDs=1.2.840.113619.2.55');
    expect(tab.opener).toBeNull();
  });

  it('closes the tab when the viewer is refused', async () => {
    const tab = { opener: null, location: { href: 'about:blank' }, close: vi.fn() };
    vi.spyOn(window, 'open').mockReturnValue(tab as unknown as Window);
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ detail: 'Forbidden' }), { status: 403 })));
    await expect(openImages('1.2.3')).rejects.toThrow('Forbidden');
    expect(tab.close).toHaveBeenCalled();
    expect(tab.location.href).toBe('about:blank');
  });

  it('escapes the study UID', () => {
    expect(viewerUrl('1.2&x=3')).toBe('/pacs/ohif/viewer?StudyInstanceUIDs=1.2%26x%3D3');
  });
});
