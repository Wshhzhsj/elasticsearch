/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.reindex;

import org.elasticsearch.action.bulk.byscroll.BulkByScrollResponse;
import org.elasticsearch.action.index.IndexRequestBuilder;

import java.util.ArrayList;
import java.util.List;

import static org.elasticsearch.index.query.QueryBuilders.termQuery;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

public class ReindexBasicTests extends ReindexTestCase {
    public void testFiltering() throws Exception {
        indexRandom(true, client().prepareIndex("source", "test", "1").setSource("foo", "a"),
                client().prepareIndex("source", "test", "2").setSource("foo", "a"),
                client().prepareIndex("source", "test", "3").setSource("foo", "b"),
                client().prepareIndex("source", "test", "4").setSource("foo", "c"));
        assertHitCount(client().prepareSearch("source").setSize(0).get(), 4);

        // Copy all the docs
        ReindexRequestBuilder copy = reindex().source("source").destination("dest", "type").refresh(true);
        assertThat(copy.get(), matcher().created(4));
        assertHitCount(client().prepareSearch("dest").setSize(0).get(), 4);

        // Now none of them
        createIndex("none");
        copy = reindex().source("source").destination("none", "type").filter(termQuery("foo", "no_match")).refresh(true);
        assertThat(copy.get(), matcher().created(0));
        assertHitCount(client().prepareSearch("none").setSize(0).get(), 0);

        // Now half of them
        copy = reindex().source("source").destination("dest_half", "type").filter(termQuery("foo", "a")).refresh(true);
        assertThat(copy.get(), matcher().created(2));
        assertHitCount(client().prepareSearch("dest_half").setSize(0).get(), 2);

        // Limit with size
        copy = reindex().source("source").destination("dest_size_one", "type").size(1).refresh(true);
        assertThat(copy.get(), matcher().created(1));
        assertHitCount(client().prepareSearch("dest_size_one").setSize(0).get(), 1);
    }

    public void testCopyMany() throws Exception {
        List<IndexRequestBuilder> docs = new ArrayList<>();
        int max = between(150, 500);
        for (int i = 0; i < max; i++) {
            docs.add(client().prepareIndex("source", "test", Integer.toString(i)).setSource("foo", "a"));
        }

        indexRandom(true, docs);
        assertHitCount(client().prepareSearch("source").setSize(0).get(), max);

        // Copy all the docs
        ReindexRequestBuilder copy = reindex().source("source").destination("dest", "type").refresh(true);
        // Use a small batch size so we have to use more than one batch
        copy.source().setSize(5);
        assertThat(copy.get(), matcher().created(max).batches(max, 5));
        assertHitCount(client().prepareSearch("dest").setSize(0).get(), max);

        // Copy some of the docs
        int half = max / 2;
        copy = reindex().source("source").destination("dest_half", "type").refresh(true);
        // Use a small batch size so we have to use more than one batch
        copy.source().setSize(5);
        copy.size(half); // The real "size" of the request.
        assertThat(copy.get(), matcher().created(half).batches(half, 5));
        assertHitCount(client().prepareSearch("dest_half").setSize(0).get(), half);
    }

    public void testCopyManyWithSlices() throws Exception {
        int workers = between(2, 10);

        List<IndexRequestBuilder> docs = new ArrayList<>();
        int max = between(150, 500);
        for (int i = 0; i < max; i++) {
            docs.add(client().prepareIndex("source", "test", Integer.toString(i)).setSource("foo", "a"));
        }

        indexRandom(true, docs);
        assertHitCount(client().prepareSearch("source").setSize(0).get(), max);

        // Copy all the docs
        ReindexRequestBuilder copy = reindex().source("source").destination("dest", "type").refresh(true).setSlices(workers);
        // Use a small batch size so we have to use more than one batch
        copy.source().setSize(5);
        assertThat(copy.get(), matcher().created(max).batches(greaterThanOrEqualTo(max / 5)).slices(hasSize(workers)));
        assertHitCount(client().prepareSearch("dest").setTypes("type").setSize(0).get(), max);

        // Copy some of the docs
        int half = max / 2;
        copy = reindex().source("source").destination("dest_half", "type").refresh(true).setSlices(workers);
        // Use a small batch size so we have to use more than one batch
        copy.source().setSize(5);
        copy.size(half); // The real "size" of the request.
        BulkByScrollResponse response = copy.get();
        assertThat(response, matcher().created(lessThanOrEqualTo((long) half)).slices(hasSize(workers)));
        assertHitCount(client().prepareSearch("dest_half").setSize(0).get(), response.getCreated());
    }
}
