// This file is part of OpenTSDB.
// Copyright (C) 2017  The OpenTSDB Authors.
//
// This program is free software: you can redistribute it and/or modify it
// under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 2.1 of the License, or (at your
// option) any later version.  This program is distributed in the hope that it
// will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
// of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser
// General Public License for more details.  You should have received a copy
// of the GNU Lesser General Public License along with this program.  If not,
// see <http://www.gnu.org/licenses/>.
package net.opentsdb.search.schemas.tsmeta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CancellationException;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.util.EntityUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.reflect.Whitebox;

import com.google.common.collect.Lists;
import com.stumbleupon.async.Deferred;
import com.stumbleupon.async.TimeoutException;

import net.opentsdb.meta.TSMeta;
import net.opentsdb.meta.UIDMeta;
import net.opentsdb.search.ESPluginConfig;
import net.opentsdb.search.ElasticSearch;
import net.opentsdb.search.TestElasticSearch;
import net.opentsdb.search.schemas.tsmeta.DefaultTSMetaSchema;
import net.opentsdb.search.schemas.tsmeta.TSMetaSchema;
import net.opentsdb.uid.UniqueId.UniqueIdType;
import net.opentsdb.utils.Config;

@RunWith(PowerMockRunner.class)
@PrepareForTest({ ElasticSearch.class, HttpAsyncClients.class })
public class TestDefaultTSMetaSchema {
  private static final String HOST = "localhost:9092";
  
  private ESPluginConfig config;
  private CloseableHttpAsyncClient client;
  private ElasticSearch es;
  private FutureCallback<HttpResponse> cb;
  private HttpUriRequest request;
  private TSMeta meta;
  private String index;
  private String doc_type;
  
  @SuppressWarnings("unchecked")
  @Before
  public void before() throws Exception {
    config = new ESPluginConfig(new Config(false));
    client = mock(CloseableHttpAsyncClient.class);
    es = mock(ElasticSearch.class);
    meta = new TSMeta("010101");
    meta.setDisplayName("Testing");
    final UIDMeta metric = new UIDMeta(UniqueIdType.METRIC, new byte[] { 1 }, 
        "sys.cpu.user");
    Whitebox.setInternalState(meta, "metric", metric);
    final UIDMeta tagk = new UIDMeta(UniqueIdType.TAGK, new byte[] { 1 },
        "host");
    final UIDMeta tagv = new UIDMeta(UniqueIdType.TAGV, new byte[] { 1 },
        "web01");
    Whitebox.setInternalState(meta, "tags", Lists.newArrayList(tagk, tagv));
    index = config.getString("tsd.search.elasticsearch.index");
    doc_type = config.getString("tsd.search.elasticsearch.tsmeta_type");
    
    when(es.httpClient()).thenReturn(client);
    when(es.host()).thenReturn(HOST);
    when(es.index()).thenReturn(index);
    when(es.config()).thenReturn(config);
    when(client.execute(any(HttpUriRequest.class), 
        any(FutureCallback.class)))
      .thenAnswer(new Answer<Void>() {
        @Override
        public Void answer(InvocationOnMock invocation) throws Throwable {
          request = (HttpUriRequest) invocation.getArguments()[0];
          cb = (FutureCallback<HttpResponse>) invocation.getArguments()[1];
          return null;
        }
      });
  }
  
  @Test
  public void ctor() {
    DefaultTSMetaSchema schema = new DefaultTSMetaSchema(es);
    assertEquals(doc_type, schema.docType());
    
    config.overrideConfig("tsd.search.elasticsearch.tsmeta_type", null);
    try {
      new DefaultTSMetaSchema(es);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
  }
  
  @Test
  public void index() throws Exception {
    final DefaultTSMetaSchema schema = new DefaultTSMetaSchema(es);
    Deferred<Object> deferred = schema.index(meta);
    try {
      deferred.join(1);
      fail("Expected TimeoutException");
    } catch (TimeoutException e) { }
    assertEquals(HOST + "/" + doc_type + "/_doc/010101",
        request.getURI().toString());
    
    // good
    cb.completed(TestElasticSearch.mockResponse(204, ""));
    assertTrue((Boolean) deferred.join());
    assertCounters(schema, 1, 0, 0);
    
    // good with async
    when(es.asyncReplication()).thenReturn(true);
    deferred = schema.index(meta);
    assertEquals(HOST + "/" + doc_type
        + "/_doc/010101?replication=async", request.getURI().toString());
    final String payload = EntityUtils.toString(((HttpPost) request)
        .getEntity());
    assertTrue(payload.contains("\"tsuid\":\"010101\""));
    assertTrue(payload.contains("\"name\":\"sys.cpu.user\""));
    assertTrue(payload.contains("\"name\":\"host\""));
    assertTrue(payload.contains("\"name\":\"web01\""));
    
    // bad
    deferred = schema.index(meta);
    cb.completed(TestElasticSearch.mockResponse(500, "WTF?"));
    try {
      deferred.join();
      fail("Expected IllegalStateException");
    } catch (IllegalStateException e) { }
    assertCounters(schema, 1, 0, 1);
    
    // cancelled
    deferred = schema.index(meta);
    cb.cancelled();
    try {
      deferred.join();
      fail("Expected CancellationException");
    } catch (CancellationException e) { }
    assertCounters(schema, 1, 0, 2);
    
    // oopsies
    deferred = schema.index(meta);
    cb.failed(new IllegalArgumentException("Boo!"));
    try {
      deferred.join();
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    assertCounters(schema, 1, 0, 3);
    
    deferred = schema.index(null);
    try {
      deferred.join();
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
  }
  
  @Test
  public void delete() throws Exception {
    final DefaultTSMetaSchema schema = new DefaultTSMetaSchema(es);
    Deferred<Object> deferred = schema.delete("010101");
    try {
      deferred.join(1);
      fail("Expected TimeoutException");
    } catch (TimeoutException e) { }
    assertEquals(HOST + "/" + doc_type + "/_doc/010101",
        request.getURI().toString());
    
    // good
    cb.completed(TestElasticSearch.mockResponse(204, ""));
    assertTrue((Boolean) deferred.join());
    assertCounters(schema, 0, 1, 0);
    
    // good with async
    when(es.asyncReplication()).thenReturn(true);
    deferred = schema.delete("010101");
    assertEquals(HOST + "/" + doc_type
        + "/_doc/010101?replication=async", request.getURI().toString());
    
    // bad
    deferred = schema.delete("010101");
    cb.completed(TestElasticSearch.mockResponse(500, "WTF?"));
    try {
      deferred.join();
      fail("Expected IllegalStateException");
    } catch (IllegalStateException e) { }
    assertCounters(schema, 0, 1, 1);
    
    // cancelled
    deferred = schema.delete("010101");
    cb.cancelled();
    try {
      deferred.join();
      fail("Expected CancellationException");
    } catch (CancellationException e) { }
    assertCounters(schema, 0, 1, 2);
    
    // oopsies
    deferred = schema.delete("010101");
    cb.failed(new IllegalArgumentException("Boo!"));
    try {
      deferred.join();
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
    assertCounters(schema, 0, 1, 3);
    
    deferred = schema.delete(null);
    try {
      deferred.join();
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) { }
  }
  
  void assertCounters(final TSMetaSchema schema, 
                      final long added,
                      final long deleted,
                      final long errors) {
    assertEquals(added, schema.added());
    assertEquals(deleted, schema.deleted());
    assertEquals(errors, schema.errors());
  }
}
