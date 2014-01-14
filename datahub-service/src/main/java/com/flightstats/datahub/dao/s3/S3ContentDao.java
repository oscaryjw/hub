package com.flightstats.datahub.dao.s3;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flightstats.datahub.dao.ContentDao;
import com.flightstats.datahub.dao.TimeIndex;
import com.flightstats.datahub.model.ChannelConfiguration;
import com.flightstats.datahub.model.Content;
import com.flightstats.datahub.model.ContentKey;
import com.flightstats.datahub.model.ValueInsertionResult;
import com.flightstats.datahub.util.DataHubKeyGenerator;
import com.google.common.base.Optional;
import com.google.common.io.ByteStreams;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import org.apache.curator.framework.CuratorFramework;
import org.apache.zookeeper.CreateMode;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.MediaType;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * This uses S3 for Content and ZooKeeper for TimeIndex
 */
public class S3ContentDao implements ContentDao, TimeIndexDao {

    private final static Logger logger = LoggerFactory.getLogger(S3ContentDao.class);

    private final DataHubKeyGenerator keyGenerator;
    private final AmazonS3 s3Client;
    private final CuratorFramework curator;
    private final String s3BucketName;
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    public S3ContentDao(DataHubKeyGenerator keyGenerator,
                        AmazonS3 s3Client,
                        @Named("aws.environment") String environment,
                        CuratorFramework curator) {
        this.keyGenerator = keyGenerator;
        this.s3Client = s3Client;
        this.curator = curator;
        this.s3BucketName = "deihub-" + environment;
    }

    @Override
    public ValueInsertionResult write(String channelName, Content content, Optional<Integer> ttlSeconds) {
        //todo - gfm - 1/3/14 - what happens if one or the other fails?
        ContentKey key = keyGenerator.newKey(channelName);
        DateTime dateTime = new DateTime();
        writeS3(channelName, content, key);
        writeIndex(channelName, dateTime, key);
        return new ValueInsertionResult(key, dateTime.toDate());
    }

    public void writeIndex(String channelName, DateTime dateTime, ContentKey key) {
        //todo - gfm - 1/9/14 - add timing
        String path = TimeIndex.getPath(channelName, dateTime, key);
        try {
            curator.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(path);
        } catch (Exception e) {
            logger.warn("unable to create " + path, e);
            throw new RuntimeException(e);
        }
    }

    private void writeS3(String channelName, Content content, ContentKey key) {
        //todo - gfm - 1/9/14 - add timing
        String s3Key = getS3ContentKey(channelName, key);
        //todo - gfm - 1/9/14 - this could use streaming if the content length is specified
        InputStream stream = new ByteArrayInputStream(content.getData());
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(content.getData().length);
        if (content.getContentType().isPresent()) {
            metadata.setContentType(content.getContentType().get());
            metadata.addUserMetadata("type", content.getContentType().get());
        } else {
            metadata.addUserMetadata("type", "none");
        }
        if (content.getContentLanguage().isPresent()) {
            metadata.addUserMetadata("language", content.getContentLanguage().get());
        } else {
            metadata.addUserMetadata("language", "none");
        }

        PutObjectRequest request = new PutObjectRequest(s3BucketName, s3Key, stream, metadata);
        s3Client.putObject(request);
    }

    @Override
    public Content read(String channelName, ContentKey key) {
        try {
            S3Object object = s3Client.getObject(s3BucketName, getS3ContentKey(channelName, key));
            byte[] bytes = ByteStreams.toByteArray(object.getObjectContent());
            ObjectMetadata metadata = object.getObjectMetadata();
            Map<String, String> userData = metadata.getUserMetadata();
            String type = userData.get("type");
            Optional<String> contentType = Optional.absent();
            if (!type.equals("none")) {
                contentType = Optional.of(type);
            }
            String language = userData.get("language");
            Optional<String> contentLang = Optional.absent();
            if (!language.equals("none")) {
                contentLang = Optional.of(language);
            }
            Date lastModified = metadata.getLastModified();
            return new Content(contentType, contentLang, bytes, lastModified.getTime());
        } catch (AmazonClientException e) {
            logger.info("unable to get " + channelName + " " + key.keyToString() + " " + e.getMessage());
        } catch (IOException e) {
            logger.warn("unable to get " + channelName + " " + key.keyToString(), e);
        }
        return null;
    }

    @Override
    public void writeIndices(String channelName, String dateTime, List<String> keys) {
        try {
            String s3Key = getS3IndexKey(channelName, dateTime);
            byte[] bytes = mapper.writeValueAsBytes(keys);
            InputStream stream = new ByteArrayInputStream(bytes);
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(bytes.length);
            metadata.setContentType(MediaType.APPLICATION_JSON);
            PutObjectRequest request = new PutObjectRequest(s3BucketName, s3Key, stream, metadata);
            s3Client.putObject(request);
        } catch (Exception e) {
            logger.warn("unable to create index " + channelName + dateTime + keys, e);
        }
    }

    @Override
    public Collection<ContentKey> getKeys(String channelName, DateTime dateTime) {
        String hashTime = TimeIndex.getHash(dateTime);
        try {
            return getKeysS3(channelName, hashTime);
        } catch (Exception e) {
            logger.info("unable to find keys in S3 " + channelName + hashTime + e.getMessage());
        }
        try {
            return getKeysZookeeper(channelName, hashTime);
        } catch (Exception e) {
            logger.info("unable to find keys in ZK " + channelName + hashTime + e.getMessage());
        }
        return Collections.emptyList();
    }

    private Collection<ContentKey> getKeysZookeeper(String channelName, String hashTime) throws Exception {
        List<String> ids = curator.getChildren().forPath(TimeIndex.getPath(channelName, hashTime));
        return convertIds(ids);
    }

    private Collection<ContentKey> getKeysS3(String channelName, String hashTime) throws IOException {
        String s3Key = getS3IndexKey(channelName, hashTime);
        S3Object object = s3Client.getObject(s3BucketName, s3Key);
        byte[] bytes = ByteStreams.toByteArray(object.getObjectContent());
        List<String> ids = mapper.readValue(bytes, new TypeReference<List<String>>() { });
        return convertIds(ids);
    }

    private Collection<ContentKey> convertIds(List<String> ids) {
        List<ContentKey> keys = new ArrayList<>();
        for (String id : ids) {
            keys.add(getKey(id).get());
        }
        Collections.sort(keys);
        return keys;
    }

    private String getS3ContentKey(String channelName, ContentKey key) {
        return channelName + "/content/" + key.keyToString();
    }

    private String getS3IndexKey(String channelName, String dateTime) {
        return channelName + "/index/" + dateTime;
    }

    @Override
    public void initialize() {
        try {
            ListObjectsRequest listObjectsRequest = new ListObjectsRequest().withBucketName(s3BucketName).withMaxKeys(0);
            s3Client.listObjects(listObjectsRequest);
            logger.info("bucket exists " + s3BucketName);
        } catch (AmazonClientException e) {
            logger.info("creating " + s3BucketName);
            CreateBucketRequest createBucketRequest = new CreateBucketRequest(s3BucketName, Region.US_Standard);
            Bucket bucket = s3Client.createBucket(createBucketRequest);
            logger.info("created " + bucket);
        }
    }

    @Override
    public void initializeChannel(ChannelConfiguration config) {
        keyGenerator.seedChannel(config.getName());
        modifyLifeCycle(config);
    }

    @Override
    public Optional<ContentKey> getKey(String id) {
        return keyGenerator.parse(id);
    }

    @Override
    public void delete(String channelName) {
        new Thread(new S3Deleter(channelName, s3BucketName, s3Client)).start();
    }

    @Override
    public void updateChannel(ChannelConfiguration config) {
        modifyLifeCycle(config);
    }

    private void modifyLifeCycle(ChannelConfiguration config) {
        //todo - gfm - 1/7/14 - this should happen in an system wide lock on ChannelConfig
        // or it should be triggered occasionally via ChannelMetadata

        if (config.getTtlMillis() == null) {
            return;
        }
        long days = TimeUnit.DAYS.convert(config.getTtlMillis(), TimeUnit.MILLISECONDS) + 1;
        BucketLifecycleConfiguration lifecycleConfig = s3Client.getBucketLifecycleConfiguration(s3BucketName);
        logger.info("found config " + lifecycleConfig);
        String namePrefix = config.getName() + "/";
        BucketLifecycleConfiguration.Rule newRule = new BucketLifecycleConfiguration.Rule()
                .withPrefix(namePrefix)
                .withId(config.getName())
                .withExpirationInDays((int) days)
                .withStatus(BucketLifecycleConfiguration.ENABLED);
        if (lifecycleConfig == null) {
            ArrayList<BucketLifecycleConfiguration.Rule> rules = new ArrayList<>();
            rules.add(newRule);
            lifecycleConfig = new BucketLifecycleConfiguration(rules);
        } else {
            BucketLifecycleConfiguration.Rule toRemove = null;
            List<BucketLifecycleConfiguration.Rule> rules = lifecycleConfig.getRules();
            for (BucketLifecycleConfiguration.Rule rule : rules) {
                if (rule.getPrefix().equals(namePrefix)) {
                    toRemove = rule;
                }
            }
            if (toRemove != null) {
                logger.info("removing rule " + toRemove.getPrefix());
                rules.remove(toRemove);
            }
            rules.add(newRule);
        }

        s3Client.setBucketLifecycleConfiguration(s3BucketName, lifecycleConfig);
    }


}
