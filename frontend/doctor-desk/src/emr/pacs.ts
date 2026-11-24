import { endpoints } from '../api/endpoints';

/** The OHIF viewer for one study, served by the PACS through the gateway. */
export const viewerUrl = (studyInstanceUid: string) =>
  `/pacs/ohif/viewer?StudyInstanceUIDs=${encodeURIComponent(studyInstanceUid)}`;

/**
 * Opens a study's images in a new tab. The tab is opened inside the click (so popup blockers
 * allow it), then pointed at the viewer once the gateway has set the viewer cookie.
 */
export async function openImages(studyInstanceUid: string): Promise<void> {
  const tab = window.open('about:blank', '_blank');
  try {
    await endpoints.openViewerSession();
    if (tab) {
      tab.opener = null;
      tab.location.href = viewerUrl(studyInstanceUid);
    } else {
      window.location.assign(viewerUrl(studyInstanceUid));
    }
  } catch (error) {
    tab?.close();
    throw error;
  }
}
