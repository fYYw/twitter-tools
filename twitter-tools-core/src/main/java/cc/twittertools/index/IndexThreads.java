package cc.twittertools.index;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import cc.twittertools.corpus.data.StatusStream;
import com.google.common.base.CharMatcher;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.apache.lucene.document.*;
import org.apache.lucene.index.IndexWriter;
import twitter4j.*;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static cc.twittertools.index.IndexStatuses.*;

class IndexThreads {
  private static final Logger LOG = Logger.getLogger(IndexThreads.class);

  private static final Object POISON_PILL = new Object();

  final IngestRatePrinter printer;
  final CountDownLatch startLatch = new CountDownLatch(1);
  final AtomicBoolean stop;
  final AtomicBoolean failed;
  final StatusStream stream;
  final Thread[] threads;
  final BlockingQueue<Status> blockingQueue;
  final StatusProducer statusProducer;

  public IndexThreads(IndexWriter w, FieldType textOptions,
                      StatusStream stream, int numThreads, int docCountLimit, boolean printDPS, long maxId, LongOpenHashSet deletes) throws IOException, InterruptedException {

    this.stream = stream;
    threads = new Thread[numThreads];

    final CountDownLatch stopLatch = new CountDownLatch(numThreads);
    final AtomicInteger prodCount = new AtomicInteger();
    final AtomicInteger docCount = new AtomicInteger();
    stop = new AtomicBoolean(false);
    failed = new AtomicBoolean(false);

    blockingQueue = new ArrayBlockingQueue<Status>(100000);
    statusProducer = new StatusProducer(blockingQueue, stream, numThreads, maxId, deletes, prodCount, stop);

    for (int thread = 0; thread < numThreads; thread++) {
      threads[thread] = new IndexThread(startLatch, stopLatch, w, textOptions, blockingQueue, docCountLimit, docCount, stop, failed);
      threads[thread].start();
    }

    Thread.sleep(10);

    statusProducer.start();

    if (printDPS) {
      printer = new IngestRatePrinter(prodCount, docCount, stop);
      printer.start();
    } else {
      printer = null;
    }
  }

  public void start() {
    startLatch.countDown();
  }

  public void stop() throws InterruptedException, IOException {
    stop.getAndSet(true);
    for (Thread t : threads) {
      t.join();
    }
    if (printer != null) {
      printer.join();
    }
    stream.close();
  }

  public boolean done() {
    for (Thread t : threads) {
      if (t.isAlive()) {
        return false;
      }
    }

    return true;
  }

  private static class IndexThread extends Thread {
    public static final String RT = "^/?RT:?\\s*:?|\\s/?RT:?\\s*:?|\\(RT:?.*\\)";
    public static final Pattern RT_PATTERN;

    public static final int MIN_CLEAN_TEXT_LENGTH = 20;
    public static final String EN = "en";

    private final BlockingQueue blockingQueue;
    private final int numTotalDocs;
    private final IndexWriter w;
    private final FieldType textOptions;
    private final AtomicInteger count;
    private final CountDownLatch startLatch;
    private final CountDownLatch stopLatch;
    private final AtomicBoolean failed;
    private final DocState docState;

    static {
      synchronized (IndexThread.class) {
        RT_PATTERN = Pattern.compile(RT);
      }
    }

    public IndexThread(CountDownLatch startLatch, CountDownLatch stopLatch, IndexWriter w, FieldType textOptions,
                       BlockingQueue blockingQueue, int numTotalDocs, AtomicInteger count,
                       AtomicBoolean stop, AtomicBoolean failed) {
      this.startLatch = startLatch;
      this.stopLatch = stopLatch;
      this.w = w;
      this.textOptions = textOptions;
      this.blockingQueue = blockingQueue;
      this.numTotalDocs = numTotalDocs;
      this.count = count;
      this.failed = failed;
      docState = new DocState(textOptions);
    }

    @Override
    public void run() {
      try {
        final long tStart = System.currentTimeMillis();

        try {
          startLatch.await();
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return;
        }

        while (true) {
          Object element = blockingQueue.take();
          if (POISON_PILL.equals(element))
            break;

          Status status = (Status) element;

          //Check twitter lang field
          String lang = status.getLang();
          if (lang == null || !EN.equals(lang)) continue;

          Status quotedStatus = status.getQuotedStatus();
          Status retweetedStatus = status.getRetweetedStatus();

          String text = status.getText();
          int textLength = text.length();
          StringBuffer buf = new StringBuffer(text);

          URLEntity[] urlEntities = status.getURLEntities();
          for (URLEntity urlEntity : urlEntities) {
            if (urlEntity.getStart() < textLength) {
              int length = urlEntity.getEnd() - urlEntity.getStart();
              buf.replace(urlEntity.getStart(), urlEntity.getEnd(), StringUtils.repeat(" ", length));
            }
          }

          MediaEntity[] mediaEntities = status.getMediaEntities();
          for (MediaEntity mediaEntity : mediaEntities) {
            if (mediaEntity.getStart() < textLength) {
              int length = mediaEntity.getEnd() - mediaEntity.getStart();
              buf.replace(mediaEntity.getStart(), mediaEntity.getEnd(), StringUtils.repeat(" ", length));
            }
          }

          HashtagEntity[] hashtagEntities = status.getHashtagEntities();
          List<String> splittedHashtags = new LinkedList<>();
          for (HashtagEntity hashtagEntity : hashtagEntities) {
            if (hashtagEntity.getStart() < textLength) {
              // remove # symbol
              buf.setCharAt(hashtagEntity.getStart(), ' ');

              if (hashtagEntity.getEnd() <= textLength) {
                String[] split = buf.substring(hashtagEntity.getStart(), hashtagEntity.getEnd()).split("(?=\\p{Upper})");
                for (String s : split)
                  splittedHashtags.add(s);
              } else {
                int length = hashtagEntity.getEnd() - hashtagEntity.getStart();
                buf.replace(hashtagEntity.getStart(), hashtagEntity.getEnd(), StringUtils.repeat(" ", length));
              }
            }
          }

          //Add splitted hashtags to the end of the text
          for (String s : splittedHashtags)
            buf.append(" " + s);

          SymbolEntity[] symbolEntities = status.getSymbolEntities();
          for (SymbolEntity symbolEntity : symbolEntities) {
            if (symbolEntity.getStart() < textLength) {
              int length = symbolEntity.getEnd() - symbolEntity.getStart();
              buf.replace(symbolEntity.getStart(), symbolEntity.getEnd(), StringUtils.repeat(" ", length));
            }
          }

          UserMentionEntity[] mentionEntities = status.getUserMentionEntities();
          for (UserMentionEntity mentionEntity : mentionEntities) {
            if (mentionEntity.getStart() < textLength) {
              int length = mentionEntity.getEnd() - mentionEntity.getStart();
              buf.replace(mentionEntity.getStart(), mentionEntity.getEnd(), StringUtils.repeat(" ", length));
            }
          }

          // remove long empty spaces
          String cleanText = buf.toString().replaceAll("\\s\\s+", " ").trim();
          cleanText = RT_PATTERN.matcher(cleanText).replaceAll("").trim();

          //Discard very short tweets
          int cleanTextLength = cleanText.length();
          if (cleanTextLength < MIN_CLEAN_TEXT_LENGTH) continue;

          //Discard tweets that are fully capitalized after stripping of special chars
          if (CharMatcher.javaUpperCase().matchesAllOf(cleanText.replaceAll("[^A-Za-z0-9]", ""))) continue;

          long id = status.getId();
          docState.id.setLongValue(id);
          docState.epoch.setLongValue(status.getCreatedAt().getTime() / 1000L);
          docState.text.setStringValue(text);
          docState.text_english.setStringValue(cleanText);
          docState.lang.setStringValue(lang);

          // user info
          docState.screenName.setStringValue(status.getUser().getScreenName());
          docState.friendsCount.setIntValue(status.getUser().getFriendsCount());
          docState.followersCount.setIntValue(status.getUser().getFollowersCount());
          docState.statusesCount.setIntValue(status.getUser().getStatusesCount());

          // reply info
          docState.inReplytoStatusId.setLongValue(status.getInReplyToStatusId());
          docState.inReplytoUserId.setLongValue(status.getInReplyToUserId());

          // quote info
          if (quotedStatus != null) {
            docState.quotedStatusId.setLongValue(status.getQuotedStatusId());
          } else {
            docState.quotedStatusId.setLongValue(0);
          }

          // retweet info
          docState.retweetCount.setIntValue(status.getRetweetCount());
          if (retweetedStatus != null) {
            docState.retweetedStatusId.setLongValue(retweetedStatus.getId());
            docState.retweetedUserId.setLongValue(retweetedStatus.getUser().getId());
          } else {
            docState.retweetedStatusId.setLongValue(0);
            docState.retweetedUserId.setLongValue(0);
          }

          int docCount = count.incrementAndGet();
          if (numTotalDocs != -1 && docCount > numTotalDocs) {
            break;
          }
          if ((docCount % 100000) == 0) {
            LOG.info("Indexer: " + docCount + " docs... (" + (System.currentTimeMillis() - tStart) / 1000.0 + " sec)");
          }
          w.addDocument(docState.doc);
        }
      } catch (Exception e) {
        failed.set(true);
        throw new RuntimeException(e);
      } finally {
        stopLatch.countDown();
      }
    }
  }

  private static class StatusProducer extends Thread {

    private final StatusStream stream;
    private final BlockingQueue blockingQueue;
    private final int numThreads;
    private final long maxId;
    private final LongOpenHashSet deletes;
    private final AtomicInteger count;
    private final AtomicBoolean stop;

    public StatusProducer(BlockingQueue blockingQueue, StatusStream stream, int numThreads, long maxId, LongOpenHashSet deletes, AtomicInteger count, AtomicBoolean stop) {
      this.blockingQueue = blockingQueue;
      this.stream = stream;
      this.numThreads = numThreads;
      this.maxId = maxId;
      this.deletes = deletes;
      this.count = count;
      this.stop = stop;
    }

    @Override
    public void run() {
      long start = System.currentTimeMillis();
      LOG.info("Producer: start");

      while (!stop.get()) {
        try {
          Status status = stream.next();
          if (status == null)
            break;

          if (status.getText() == null) {
            continue;
          }

          // Skip deletes tweetids.
          if (deletes != null && deletes.contains(status.getId())) {
            continue;
          }

          if (status.getId() > maxId) {
            continue;
          }

          blockingQueue.put(status);
          count.incrementAndGet();
        } catch (Exception ex) {
        }
      }

      try {
        for (int thread = 0; thread < numThreads; thread++) {
          blockingQueue.put(POISON_PILL);
        }
      } catch (Exception ex) {
      }

      int numProd = count.get();
      long now = System.currentTimeMillis();
      double seconds = (now - start) / 1000.0d;
      LOG.info("Producer: " + (int)(numProd / seconds) + " " + seconds + " " + numProd + " produced...");
    }
  }

  public static final class DocState {
    final Document doc;
    final Field id;
    final Field epoch;
    final Field text;
    final Field text_english;
    final Field lang;
    final Field screenName;
    final Field friendsCount;
    final Field followersCount;
    final Field statusesCount;
    final Field inReplytoStatusId;
    final Field inReplytoUserId;
    final Field quotedStatusId;
    final Field retweetedStatusId;
    final Field retweetedUserId;
    final Field retweetCount;

    DocState(FieldType textOptions) {
      doc = new Document();

      id = new LongField(StatusField.ID.name, 0, Field.Store.YES);
      doc.add(id);

      epoch = new LongField(StatusField.EPOCH.name, 0, Field.Store.YES);
      doc.add(epoch);

      text = new Field(StatusField.TEXT.name, "", textOptions);
      doc.add(text);

      text_english = new Field(StatusField.TEXT_ENGLISH.name, "", textOptions);
      doc.add(text_english);

      lang = new StringField(StatusField.LANG.name, "", Field.Store.YES);
      doc.add(lang);

      screenName = new StringField(StatusField.SCREEN_NAME.name, "", Field.Store.YES);
      doc.add(screenName);

      friendsCount = new IntField(StatusField.FRIENDS_COUNT.name, 0, Field.Store.YES);
      doc.add(friendsCount);

      followersCount = new IntField(StatusField.FOLLOWERS_COUNT.name, 0, Field.Store.YES);
      doc.add(followersCount);

      statusesCount = new IntField(StatusField.STATUSES_COUNT.name, 0, Field.Store.YES);
      doc.add(statusesCount);

      inReplytoStatusId = new LongField(StatusField.IN_REPLY_TO_STATUS_ID.name, 0, Field.Store.YES);
      doc.add(inReplytoStatusId);

      inReplytoUserId = new LongField(StatusField.IN_REPLY_TO_USER_ID.name, 0, Field.Store.YES);
      doc.add(inReplytoUserId);

      quotedStatusId = new LongField(StatusField.QUOTED_STATUS_ID.name, 0, Field.Store.YES);
      doc.add(quotedStatusId);

      retweetedStatusId = new LongField(StatusField.RETWEETED_STATUS_ID.name, 0, Field.Store.YES);
      doc.add(retweetedStatusId);

      retweetedUserId = new LongField(StatusField.RETWEETED_USER_ID.name, 0, Field.Store.YES);
      doc.add(retweetedUserId);

      retweetCount = new IntField(StatusField.RETWEET_COUNT.name, 0, Field.Store.YES);
      doc.add(retweetCount);
    }
  }

  private static class IngestRatePrinter extends Thread {

    private final AtomicInteger prodCount;
    private final AtomicInteger docCount;
    private final AtomicBoolean stop;

    public IngestRatePrinter(AtomicInteger prodCount, AtomicInteger docCount, AtomicBoolean stop) {
      this.prodCount = prodCount;
      this.docCount = docCount;
      this.stop = stop;
    }

    @Override
    public void run() {
      long time = System.currentTimeMillis();
      LOG.info("startIngest: " + time);
      final long start = time;
      int lastProdCount = prodCount.get();
      int lastDocsCount = docCount.get();
      while (!stop.get()) {
        try {
          Thread.sleep(200);
        } catch (Exception ex) {
        }
        int numProd = prodCount.get();
        int numDocs = docCount.get();

        double currentProd = numProd - lastProdCount;
        double currentDocs = numDocs - lastDocsCount;
        long now = System.currentTimeMillis();
        double seconds = (now - time) / 1000.0d;
        LOG.info("ingest: " + (now - start) + " " + (int)(currentProd / seconds) + " " + (int)(currentDocs / seconds) + " " + numProd + " " + numDocs);
        time = now;
        lastProdCount = numProd;
        lastDocsCount = numDocs;
      }
    }
  }
}
