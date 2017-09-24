package org.mengyun.tcctransaction.server.dao;

import org.apache.commons.lang3.time.DateUtils;
import org.mengyun.tcctransaction.SystemException;
import org.mengyun.tcctransaction.api.TransactionXid;
import org.mengyun.tcctransaction.repository.TransactionIOException;
import org.mengyun.tcctransaction.repository.helper.JedisCallback;
import org.mengyun.tcctransaction.repository.helper.RedisHelper;
import org.mengyun.tcctransaction.server.vo.TransactionVo;
import org.mengyun.tcctransaction.utils.ByteUtils;
import org.mengyun.tcctransaction.utils.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import redis.clients.jedis.*;

import javax.transaction.xa.Xid;
import java.text.ParseException;
import java.util.*;

/**
 * Created by changming.xie on 9/7/16.
 */
@Repository("redisTransactionDao")
public class RedisTransactionDao implements TransactionDao {

    @Autowired
    private JedisPool jedisPool;

    @Value("#{redisDomainKeyPrefix}")
    private Properties domainKeyPrefix;

    @Override
    public List<TransactionVo> findTransactions(final String domain, final Integer pageNum, final int pageSize) {


        return RedisHelper.execute(jedisPool, new JedisCallback<List<TransactionVo>>() {
            @Override
            public List<TransactionVo> doInJedis(Jedis jedis) {

                int start = (pageNum - 1) * pageSize;
                int end = pageNum * pageSize;

                ArrayList<byte[]> allKeys = new ArrayList<byte[]>(jedis.keys((domainKeyPrefix.getProperty(domain) + "*").getBytes()));

                if (allKeys.size() < start) {
                    return Collections.emptyList();
                }

                if (end > allKeys.size()) {
                    end = allKeys.size();
                }

                final List<byte[]> keys = allKeys.subList(start, end);

                try {

                    return RedisHelper.execute(jedisPool, new JedisCallback<List<TransactionVo>>() {
                        @Override
                        public List<TransactionVo> doInJedis(Jedis jedis) {

                            Pipeline pipeline = jedis.pipelined();

                            for (final byte[] key : keys) {
                                pipeline.hgetAll(key);
                            }
                            Response<List<Object>> result = pipeline.exec();

                            List<TransactionVo> list = new ArrayList<TransactionVo>();
                            for (Object data : result.get()) {
                                try {

                                    Map<byte[], byte[]> map1 = (Map<byte[], byte[]>) data;

                                    Map<String, byte[]> propertyMap = new HashMap<String, byte[]>();

                                    for (Map.Entry<byte[], byte[]> entry : map1.entrySet()) {
                                        propertyMap.put(new String(entry.getKey()), entry.getValue());
                                    }


                                    TransactionVo transactionVo = new TransactionVo();
                                    transactionVo.setDomain(domain);
                                    transactionVo.setGlobalTxId(UUID.nameUUIDFromBytes(propertyMap.get("GLOBAL_TX_ID")).toString());
                                    transactionVo.setBranchQualifier(UUID.nameUUIDFromBytes(propertyMap.get("BRANCH_QUALIFIER")).toString());
                                    transactionVo.setStatus(ByteUtils.bytesToInt(propertyMap.get("STATUS")));
                                    transactionVo.setTransactionType(ByteUtils.bytesToInt(propertyMap.get("TRANSACTION_TYPE")));
                                    transactionVo.setRetriedCount(ByteUtils.bytesToInt(propertyMap.get("RETRIED_COUNT")));
                                    transactionVo.setCreateTime(DateUtils.parseDate(new String(propertyMap.get("CREATE_TIME")), "yyyy-MM-dd HH:mm:ss"));
                                    transactionVo.setLastUpdateTime(DateUtils.parseDate(new String(propertyMap.get("LAST_UPDATE_TIME")), "yyyy-MM-dd HH:mm:ss"));
                                    transactionVo.setContentView(new String(propertyMap.get("CONTENT_VIEW")));
                                    list.add(transactionVo);

                                } catch (ParseException e) {
                                    throw new SystemException(e);
                                }
                            }

                            return list;
                        }
                    });

                } catch (Exception e) {
                    throw new TransactionIOException(e);
                }

            }
        });
    }

    @Override
    public Integer countOfFindTransactions(String domain) {
        Jedis jedis = null;
        try {
            jedis = jedisPool.getResource();
            return jedis.keys((domainKeyPrefix.getProperty(domain) + "*").getBytes()).size();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    @Override
    public boolean resetRetryCount(final String domain, final byte[] globalTxId, final byte[] branchQualifier) {

        if (domainKeyPrefix.getProperty(domain) == null) {
            return false;
        }

        final Xid xid = new TransactionXid(globalTxId, branchQualifier);

        return RedisHelper.execute(jedisPool, new JedisCallback<Boolean>() {
            @Override
            public Boolean doInJedis(Jedis jedis) {

                byte[] key = RedisHelper.getRedisKey(domainKeyPrefix.getProperty(domain), xid);
                byte[] versionKey = RedisHelper.getVersionKey(domainKeyPrefix.getProperty(domain), xid);

                byte[] versionBytes = jedis.get(versionKey);

                jedis.watch(versionKey);

                Transaction multi = jedis.multi();

                multi.set(versionKey, ByteUtils.longToBytes(ByteUtils.bytesToLong(versionBytes) + 1));
                multi.hset(key, "VERSION".getBytes(), ByteUtils.longToBytes(ByteUtils.bytesToLong(versionBytes) + 1));
                multi.hset(key, "RETRIED_COUNT".getBytes(), ByteUtils.intToBytes(0));
                List<Object> result = multi.exec();

                if (!CollectionUtils.isEmpty(result)) {
                    return true;
                }
                return false;
            }
        });
    }
}
