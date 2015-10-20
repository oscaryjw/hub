package com.flightstats.hub.channel;

import com.flightstats.hub.dao.ChannelService;
import com.flightstats.hub.dao.Request;
import com.flightstats.hub.model.ChannelContentKey;
import com.flightstats.hub.model.Content;
import com.flightstats.hub.model.ContentKey;
import com.flightstats.hub.spoke.SpokeMarshaller;
import com.google.common.base.Optional;
import com.google.common.io.ByteStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.core.UriInfo;
import java.util.Collection;
import java.util.function.Consumer;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ZipBatchBuilder {

    private final static Logger logger = LoggerFactory.getLogger(ZipBatchBuilder.class);

    public static Response build(Collection<ContentKey> keys, String channel,
                                 ChannelService channelService, UriInfo uriInfo) {
        return write((ZipOutputStream output) -> {
            for (ContentKey key : keys) {
                writeContent(uriInfo, output, key, channel, channelService);
            }
        });
    }

    public static Response buildTag(String tag, Collection<ChannelContentKey> keys,
                                    ChannelService channelService, UriInfo uriInfo) {
        return write((ZipOutputStream output) -> {
            for (ChannelContentKey key : keys) {
                writeContent(uriInfo, output, key.getContentKey(), key.getChannel(), channelService);
            }
        });
    }

    private static Response write(final Consumer<ZipOutputStream> consumer) {
        Response.ResponseBuilder builder = Response.ok((StreamingOutput) os -> {
            ZipOutputStream output = new ZipOutputStream(os);
            output.setLevel(Deflater.DEFAULT_COMPRESSION);
            consumer.accept(output);
            output.flush();
            output.close();
        });
        builder.type("application/zip");
        return builder.build();
    }

    private static void writeContent(UriInfo uriInfo, ZipOutputStream output, ContentKey key, String channel,
                                     ChannelService channelService) {
        try {
            Request request = Request.builder()
                    .channel(channel)
                    .key(key)
                    .build();
            //todo - gfm - 10/20/15 - change channelService.getValue to support cache only?
            Optional<Content> contentOptional = channelService.getValue(request);
            if (contentOptional.isPresent()) {
                String keyId = key.toUrl();
                Content content = contentOptional.get();
                ZipEntry zipEntry = new ZipEntry(keyId);
                output.putNextEntry(zipEntry);
                String metaData = SpokeMarshaller.getMetaData(content);
                zipEntry.setComment(metaData);
                long bytesCopied = ByteStreams.copy(content.getStream(), output);
                zipEntry.setSize(bytesCopied);
            } else {
                logger.warn("missing content for zip {} {}", channel, key);
            }
        } catch (Exception e) {
            logger.warn("exception zip batching to " + channel, e);
            throw new RuntimeException(e);
        }
    }


}