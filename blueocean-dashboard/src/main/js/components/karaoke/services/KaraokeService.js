import { BunkerService, logging } from '@jenkins-cd/blueocean-core-js';
import { Pager } from './Pager';

const logger = logging.logger('io.jenkins.blueocean.dashboard.karaoke.Service');
/*
 * This class provides karaoke related services.
 *
 * @export
 * @class KaraokeService
 * @extends {BunkerService}
 */
export class KaraokeService extends BunkerService {
    /**
     * Generates a pager key for [@link PagerService] to store the [@link Pager] under.
     *
     * @param {object} pipeline Pipeline that this pager belongs to.
     * @param {string} branch the name of the branch we are requesting
     * @param {string} runId Run that this pager belongs to.
     * @returns {string} key for [@link PagerService]
     */
    pagerKey(pipeline, branch, runId) {
        const key = `Details/${pipeline.organization}-${pipeline.fullName}-${branch}-${runId}`;
        logger.debug('pagerKey:', key);
        return key;
    }
    /**
     * Gets the karaoke pager
     *
     * @param {object} pipeline Pipeline that this pager belongs to.
     * @param {string} branch the name of the branch we are requesting
     * @param {string} run Run that this pager belongs to.
     * @returns {Pager} Pager for this pipelne.
     */
    karaokePager(augmenter, followAlong) {
        const {pipeline, branch, run} = augmenter;
        return this.pagerService.getPager({
            key: this.pagerKey(pipeline, branch, run.id),
            /**
             * Lazily generate the pager in case its needed.
             */
            lazyPager: () => new Pager(this, augmenter, followAlong),
        });
    }
    /**
     * Gets a detail from the store.
     *
     */
    getDetail(href) {
        return this.getItem(href);
    }

    setItems(items) {
        this.setItem(items);
    }

}
