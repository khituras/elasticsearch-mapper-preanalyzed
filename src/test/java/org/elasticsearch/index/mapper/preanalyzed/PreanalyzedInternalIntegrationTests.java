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
package org.elasticsearch.index.mapper.preanalyzed;

import static org.elasticsearch.client.Requests.putMappingRequest;
import static org.elasticsearch.index.query.QueryBuilders.matchPhraseQuery;
import static org.elasticsearch.index.query.QueryBuilders.matchQuery;
import static org.elasticsearch.index.query.QueryBuilders.queryStringQuery;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsRequest;
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsResponse;
import org.elasticsearch.action.search.SearchAction;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.plugin.mapper.preanalyzed.MapperPreAnalyzedPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.test.ESIntegTestCase;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;

public class PreanalyzedInternalIntegrationTests extends ESIntegTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Collections.singleton(MapperPreAnalyzedPlugin.class);
    }

    /**
     * Check that the analysis conforms to the "keyword" analyzer
     *
     * @throws Exception If something goes wrong.
     */
    public void testAnalysis() throws Exception {
        // note: you must possibly configure your IDE / build system to copy the
        // test resources into the build folder
        String mapping = IOUtils.toString(getClass().getResourceAsStream("/simpleMapping.json"), "UTF-8");
        assertAcked(client().admin().indices().prepareCreate("test").addMapping("document", mapping, XContentType.JSON));
        // Put the preanalyzed mapping
        client().admin().indices().putMapping(putMappingRequest("test").type("document").source(mapping, XContentType.JSON)).actionGet();
        assertEquals("Black", client().admin().indices().prepareAnalyze("Black").setIndex("test").setField("title")
                .execute().get().getTokens().get(0).getTerm());
    }


    @SuppressWarnings("unchecked")
    public void testSimpleIndex() throws Exception {
        String mapping = IOUtils.toString(getClass().getResourceAsStream("/simpleMapping.json"), "UTF-8");
        byte[] docBytes = IOUtils.toByteArray(getClass().getResourceAsStream("/preanalyzedDoc.json"));

        assertAcked(client().admin().indices().prepareCreate("test").addMapping("document", mapping, XContentType.JSON));

        // Put the preanalyzed mapping check that it is there indeed
        client().admin().indices().putMapping(putMappingRequest("test").type("document").source(mapping, XContentType.JSON)).actionGet();
        GetMappingsResponse actionGet =
                client().admin().indices().getMappings(new GetMappingsRequest().indices("test")).get();
        Map<String, Object> mappingProperties =
                (Map<String, Object>) actionGet.getMappings().get("test").get("document").getSourceAsMap()
                        .get("properties");
        assertTrue(mappingProperties.keySet().contains("title"));
        Map<String, Object> titleMapping = (Map<String, Object>) mappingProperties.get("title");
        assertEquals("preanalyzed", titleMapping.get("type"));
        assertEquals(true, titleMapping.get("store"));
        assertEquals("with_positions_offsets", titleMapping.get("term_vector"));
        assertEquals("keyword", titleMapping.get("analyzer"));

        index("test", "document", "1", XContentHelper.convertToJson(new BytesArray(docBytes), false, false, XContentType.JSON));
        refresh();

        SearchResponse searchResponse = client().prepareSearch("test")
                .setQuery(queryStringQuery("Black").defaultField("title")).setSize(0).setIndices("test").execute()
                .get();
        assertEquals(1l, searchResponse.getHits().getTotalHits().value);

        searchResponse = client().prepareSearch("test")
                .setQuery(queryStringQuery("black").defaultField("title")).setSize(0).setIndices("test").execute()
                .get();
        assertEquals(0l, searchResponse.getHits().getTotalHits().value);

        // actually, 'Black' and 'hero' are "on top of each other", 'hero' has a
        // position increment of 0 and comes after
        // 'Black'. we need to set a phrase slop to allow a match.
        searchResponse = client().prepareSearch("test")
                .setQuery(matchPhraseQuery("title", "Black hero").analyzer("whitespace").slop(1)).setSize(0)
                .setIndices("test").execute().get();
        assertEquals(1l, searchResponse.getHits().getTotalHits().value);

        searchResponse = client().prepareSearch("test")
                .setQuery(matchPhraseQuery("title", "Beauty hero").analyzer("whitespace").slop(0)).setSize(0)
                .setIndices("test").execute().get();
        assertEquals(0l, searchResponse.getHits().getTotalHits().value);

        searchResponse = client().prepareSearch("test")
                .setQuery(matchPhraseQuery("title", "Beauty hero").analyzer("whitespace").slop(3)).setSize(0)
                .setIndices("test").execute().get();
        assertEquals(1l, searchResponse.getHits().getTotalHits().value);

        searchResponse = client().prepareSearch("test")
                .setQuery(queryStringQuery("Anne Sewell").defaultField("author")).setSize(0).setIndices("test")
                .execute().get();
        assertEquals(1l, searchResponse.getHits().getTotalHits().value);

        searchResponse = client().prepareSearch("test")
                .setQuery(queryStringQuery("1877").defaultField("year")).setSize(0).setIndices("test").execute().get();
        assertEquals(1l, searchResponse.getHits().getTotalHits().value);

        searchResponse = client().prepareSearch("test").setQuery(matchQuery("title", "Black")).storedFields("title")
                .execute().actionGet();
        assertEquals(1, searchResponse.getHits().getTotalHits().value);
        SearchHit searchHit = searchResponse.getHits().getHits()[0];

        assertTrue(((String) searchHit.field("title").getValue()).startsWith("Black Beauty"));
    }
}
