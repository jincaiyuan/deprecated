package com.walker.bloomfilter.service.impl;

import com.walker.bloomfilter.model.CreditModel;
import com.walker.bloomfilter.model.Operator;
import com.walker.bloomfilter.service.CreditModelParserService;
import com.walker.bloomfilter.util.DateUtil;
import com.walker.bloomfilter.util.MurmurHash;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.NumberUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Date;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor(onConstructor_ = {@Autowired})
public class CreditModelParserServiceImpl implements CreditModelParserService {

    @Value("${credit.local-path}")
    private String localPath;

    @Value("${redis.expire-time}")
    private int expireTime;

    @Value("${mongo.storage.prefix}")
    private int mongoInPrefix;

    private final RedisTemplate<String, String> redisTemplate;
    private final MongoTemplate mongoTemplate;

    @Override
    public void parseFile() {
        String today = DateUtil.getCurrentDateString("yyyyMMdd");
        File dir = new File(localPath + today);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        File[] files = dir.listFiles();
        if (files == null) {
            log.info("没有文件可以扫描");
            return;
        }
        String collectionName = mongoInPrefix + DateUtil.getCurrentDateString("yyyyMMdd");
        for (File file : files) {
            try {
                FileUtils.readLines(file, Charset.defaultCharset()).forEach(line -> {
                    CreditModel creditModel = parseLine(line, file.getName());
                    if (creditModel != null) {
                        // 插入到mongodb，每天都新建一个集合
                        mongoTemplate.insert(creditModel, collectionName);
                    }
                });
            } catch (IOException e) {
                log.error(e.getMessage());
            }
        }
        // 为 rySecret创建索引
        mongoTemplate.indexOps(collectionName).ensureIndex(new Index().on("rySecret", Sort.Direction.ASC));
    }

    private CreditModel parseLine(String line, String filename) {
        String batch = "TEST_" + new Date().getTime() + "_" + MurmurHash.hash(filename.getBytes());
        String inTime = DateUtil.getCurrentDateString("yyyyMMdd HH:mm:ss");
        String[] cells = line.split("\\|");
        if (cells.length == 6) {
            String rySecret = cells[0];
            // 使用redis判断是否重复，rySecret为键，文件名为值
            String duplication = redisTemplate.opsForValue().get(rySecret);
            if (duplication == null) {
                CreditModel creditModel = new CreditModel();
                creditModel.setBatch(batch);
                creditModel.setRySecret(rySecret);
                redisTemplate.opsForValue().set(rySecret, filename, expireTime, TimeUnit.HOURS);
                String f1 = cells[1];
                creditModel.setF1(f1);
                String f2 = cells[2];
                creditModel.setF2(f2);
                Integer cityCode = NumberUtils.parseNumber(cells[3], Integer.class);
                creditModel.setCityCode(cityCode);
                Operator operator = null;
                int op = NumberUtils.parseNumber(cells[4], Integer.class);
                if (op == 0) operator = Operator.CTCC;
                if (op == 1) operator = Operator.CMCC;
                if (op == 2) operator = Operator.CUCC;
                creditModel.setOperator(operator);
                Integer mobileSuffix = NumberUtils.parseNumber(cells[5], Integer.class);
                creditModel.setMobileSuffix(mobileSuffix);
                creditModel.setInTime(inTime);
                return creditModel;
            } else {
                log.warn("【存在重复：{}】", rySecret);
                return null;
            }
        } else {
            log.error("文件：{} 内容不符合", filename);
            return null;
        }
    }
}
