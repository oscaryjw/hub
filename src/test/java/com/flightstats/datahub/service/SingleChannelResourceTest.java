package com.flightstats.datahub.service;

import com.flightstats.datahub.dao.ChannelDao;
import com.flightstats.datahub.model.ChannelConfiguration;
import com.flightstats.datahub.model.DataHubKey;
import com.flightstats.datahub.model.ValueInsertionResult;
import com.flightstats.datahub.model.exception.NoSuchChannelException;
import com.flightstats.datahub.service.eventing.SubscriptionDispatcher;
import com.flightstats.rest.HalLink;
import com.flightstats.rest.Linked;
import org.junit.Before;
import org.junit.Test;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.Date;
import java.util.concurrent.Callable;

import static org.hamcrest.core.IsCollectionContaining.hasItems;
import static org.junit.Assert.*;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;

public class SingleChannelResourceTest {

	private String channelName;
	private String contentType;
	private URI channelUri;
	private ChannelDao dao;
	private SubscriptionDispatcher subscriptionDispatcher;
	private ChannelHypermediaLinkBuilder linkBuilder;
	public static final Date CREATION_DATE = new Date(12345L);
	private ChannelConfiguration channelConfig;
	private DataHubKey dataHubKey;
	private URI itemUri;
	private ChannelLockExecutor channelLockExecutor;

	@Before
	public void setup() {
		channelName = "UHF";
		contentType = "text/plain";
		channelUri = URI.create("http://testification.com/channel/spoon");
		URI requestUri = URI.create("http://testification.com/channel/spoon");
		URI latestUri = URI.create("http://testification.com/channel/spoon/latest");
		itemUri = URI.create("http://testification.com/channel/spoon/888item888");
		dataHubKey = new DataHubKey(CREATION_DATE, (short) 12);
		channelConfig = new ChannelConfiguration(channelName, CREATION_DATE, null);

		UriInfo urlInfo = mock(UriInfo.class);
		dao = mock(ChannelDao.class);
		subscriptionDispatcher = mock(SubscriptionDispatcher.class);
		linkBuilder = mock(ChannelHypermediaLinkBuilder.class);
		channelLockExecutor = mock(ChannelLockExecutor.class);

		when(urlInfo.getRequestUri()).thenReturn(requestUri);
		when(dao.channelExists(channelName)).thenReturn(true);
		when(linkBuilder.buildChannelUri(channelConfig)).thenReturn(channelUri);
		when(linkBuilder.buildChannelUri(channelName)).thenReturn(channelUri);
		when(linkBuilder.buildLatestUri(channelConfig)).thenReturn(latestUri);
		when(linkBuilder.buildItemUri(dataHubKey)).thenReturn(itemUri);
	}

	@Test
	public void testGetChannelMetadataForKnownChannel() throws Exception {

		UriInfo uriInfo = mock(UriInfo.class);
		when(dao.channelExists(anyString())).thenReturn(true);
		when(dao.getChannelConfiguration(channelName)).thenReturn(channelConfig);
		when(uriInfo.getRequestUri()).thenReturn(channelUri);

		SingleChannelResource testClass = new SingleChannelResource(dao, linkBuilder, null, subscriptionDispatcher);

		Linked<ChannelConfiguration> result = testClass.getChannelMetadata(channelName);
		assertEquals(channelConfig, result.getObject());
		HalLink selfLink = result.getHalLinks().getLinks().get(0);
		HalLink latestLink = result.getHalLinks().getLinks().get(1);
		assertEquals(new HalLink("self", channelUri), selfLink);
		assertEquals(new HalLink("latest", URI.create(channelUri.toString() + "/latest")), latestLink);
	}

	@Test
	public void testGetChannelMetadataForUnknownChannel() throws Exception {
		when(dao.channelExists("unknownChannel")).thenReturn(false);

		SingleChannelResource testClass = new SingleChannelResource(dao, linkBuilder, null, subscriptionDispatcher);
		try {
			testClass.getChannelMetadata("unknownChannel");
			fail("Should have thrown a 404");
		} catch (WebApplicationException e) {
			Response response = e.getResponse();
			assertEquals(Response.Status.NOT_FOUND.getStatusCode(), response.getStatus());
		}
	}

	@Test
	public void testInsertValue() throws Exception {
		byte[] data = new byte[]{'b', 'o', 'l', 'o', 'g', 'n', 'a'};

		HalLink selfLink = new HalLink("self", itemUri);
		HalLink channelLink = new HalLink("channel", channelUri);
		ValueInsertionResult expectedResponse = new ValueInsertionResult(dataHubKey);

		ChannelLockExecutor channelLockExecutor = new ChannelLockExecutor();

		when(dao.insert(channelName, contentType, data)).thenReturn(new ValueInsertionResult(dataHubKey));

		SingleChannelResource testClass = new SingleChannelResource(dao, linkBuilder, channelLockExecutor, subscriptionDispatcher);
		Response response = testClass.insertValue(contentType, channelName, data);
		Linked<ValueInsertionResult> result = (Linked<ValueInsertionResult>) response.getEntity();

		assertThat(result.getHalLinks().getLinks(), hasItems(selfLink, channelLink));
		ValueInsertionResult insertionResult = result.getObject();

		assertEquals(expectedResponse, insertionResult);
		assertEquals(itemUri, response.getMetadata().getFirst("Location"));
	}

	@Test
	public void testInsertPerformsDispatch() throws Exception {
		byte[] data = new byte[]{'b', 'o', 'l', 'o', 'g', 'n', 'a'};
		ChannelLockExecutor channelLockExecutor = new ChannelLockExecutor();

		when(dao.insert(channelName, contentType, data)).thenReturn(new ValueInsertionResult(dataHubKey));

		SingleChannelResource testClass = new SingleChannelResource(dao, linkBuilder, channelLockExecutor, subscriptionDispatcher);
		testClass.insertValue(contentType, channelName, data);
		verify(subscriptionDispatcher).dispatch(channelName, itemUri);
	}

	@Test(expected = NoSuchChannelException.class)
	public void testInsertValue_unknownChannel() throws Exception {
		byte[] data = new byte[]{'b', 'o', 'l', 'o', 'g', 'n', 'a'};

		ChannelDao dao = mock(ChannelDao.class);

		when(dao.channelExists(anyString())).thenReturn(false);
		when(channelLockExecutor.execute(eq(channelName), any(Callable.class))).thenThrow(
				new NoSuchChannelException("No such channel: " + channelName, new RuntimeException()));

		SingleChannelResource testClass = new SingleChannelResource(dao, linkBuilder, channelLockExecutor, subscriptionDispatcher);
		testClass.insertValue(contentType, channelName, data);
	}
}
