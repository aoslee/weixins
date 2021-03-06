package com.wja.weixin.service;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.wja.base.common.service.CommService;
import com.wja.base.util.AudioUtil;
import com.wja.base.util.Log;
import com.wja.base.web.AppContext;
import com.wja.wechat.media.MediaManager;
import com.wja.weixin.dao.NeedDownloadFileDao;
import com.wja.weixin.dao.UpFileDao;
import com.wja.weixin.entity.Message;
import com.wja.weixin.entity.NeedDownloadFile;
import com.wja.weixin.entity.Product;
import com.wja.weixin.entity.UpFile;
import com.wja.weixin.entity.WorkOrder;

@Service
public class NeedDownloadFileService extends CommService<NeedDownloadFile>
{
    @Autowired
    private UpFileDao upFileDao;
    
    @Autowired
    private NeedDownloadFileDao downloadDao;
    
    @Autowired
    private ProductService productService;
    
    @Autowired
    private WorkOrderService workOrderService;
    
    @Autowired
    private MessageService messageService;
    
    private ExecutorService threadPool = Executors.newCachedThreadPool();
    
    public void addNeeddownloadFile(String busiId, String serverIds, NeedDownloadFile.Type type)
    {
        if (StringUtils.isBlank(serverIds))
        {
            return;
        }
        Log.info("传入 addNeeddownloadFile的参数 busiId[" + busiId + "] serverIds[" + serverIds + "]");
        
        String[] sids = serverIds.split(";");
        // 先存储到数据库中，在异步下载
        this.saveNeedDownloadFilesToDB(busiId, sids, type.name());
        
        switch (type)
        {
            case AUTH_CRIED:
            case BRAND_AUTHOR:
                this.downloadAuthImg(busiId, sids, type);
                break;
            case PRODUCT_IMG:
                this.downloadProductImg(busiId, sids, type);
                break;
            case WORK_ORDER_IMG:
                this.downloadWorkOrderImg(busiId, sids, type);
                break;
            case MESSAGE_VOICE:
                this.downloadMessVoices(busiId, sids, type);
                break;
            case WORK_ORDER_VOICE:
                this.downloadWorkOrderVoices(busiId, sids, type);
                break;
            
        }
    }
    
    private void saveNeedDownloadFilesToDB(String busiId, String[] sids, String type)
    {
        List<NeedDownloadFile> ndfs = new ArrayList<>();
        for (String sid : sids)
        {
            ndfs.add(new NeedDownloadFile(sid, busiId, type));
        }
        
        this.downloadDao.save(ndfs);
    }
    
    private void downloadMessVoices(String busiId, String[] sids, NeedDownloadFile.Type type)
    {
        for (String xid : sids)
        {
            if (StringUtils.isBlank(xid))
            {
                continue;
            }
            
            final String sid = xid.split("\\|\\|")[0];
            
            this.threadPool.submit(new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        String voPath = commonDownLoadFile(sid.substring(4), type);
                        
                        if (voPath.endsWith(".amr"))
                        {
                            // 转mp3
                            voPath = changeAmrToMp3(voPath);
                        }
                        
                        synchronized (busiId)
                        {
                            Message m = messageService.get(Message.class, busiId);
                            String voices = m.getVoices();
                            m.setVoices(voices.replace(sid, voPath));
                            messageService.update(m);
                            downloadDao.delete(xid);
                        }
                    }
                    catch (Exception e)
                    {
                    }
                    
                }
            });
        }
    }
    
    private String changeAmrToMp3(String sourceFile)
    {
        
        String rootSavePath = AppContext.getSysParam("wx.upload.save.rootdir");
        
        String newPath = sourceFile.substring(0, sourceFile.lastIndexOf('.')) + ".mp3";
        String oldFile = rootSavePath + sourceFile;
        AudioUtil.changeAmrToMp3(oldFile, rootSavePath + newPath);
        File f = new File(oldFile);
        try
        {
            f.delete();
        }
        catch (Exception e)
        {
            // 该异常忽略
        }
        return newPath;
    }
    
    private void downloadWorkOrderVoices(String busiId, String[] sids, NeedDownloadFile.Type type)
    {
        for (String xid : sids)
        {
            if (StringUtils.isBlank(xid))
            {
                continue;
            }
            
            final String sid = xid.split("\\|\\|")[0];
            
            this.threadPool.submit(new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        String voPath = commonDownLoadFile(sid.substring(4), type);
                        
                        if (voPath.endsWith(".amr"))
                        {
                            // 转mp3
                            voPath = changeAmrToMp3(voPath);
                        }
                        
                        synchronized (busiId)
                        {
                            WorkOrder m = workOrderService.get(WorkOrder.class, busiId);
                            String voices = m.getVoices();
                            m.setVoices(voices.replace(sid, voPath));
                            workOrderService.update(m);
                            downloadDao.delete(xid);
                        }
                    }
                    catch (Exception e)
                    {
                    }
                    
                }
            });
        }
    }
    
    private void downloadAuthImg(String busiId, String[] sids, NeedDownloadFile.Type type)
    {
        for (String sid : sids)
        {
            this.threadPool.submit(new AuthImgDownTask(sid, busiId, type, upFileDao, this.downloadDao));
        }
    }
    
    private void downloadWorkOrderImg(String busiId, String[] sids, NeedDownloadFile.Type type)
    {
        List<Future<String>> list = new ArrayList<>();
        for (String sid : sids)
        {
            list.add(this.threadPool.submit(new Callable<String>()
            {
                @Override
                public String call()
                    throws Exception
                {
                    return commonDownLoadFile(sid, type);
                }
            }));
        }
        
        this.threadPool.submit(new Runnable()
        {
            @Override
            public void run()
            {
                String img = "";
                int i = 0;
                try
                {
                    for (Future<String> fu : list)
                    {
                        if (i++ == 0)
                        {
                            img += fu.get();
                        }
                        else
                        {
                            img += ";" + fu.get();
                        }
                    }
                    
                    WorkOrder w = workOrderService.get(WorkOrder.class, busiId);
                    w.setImg(img);
                    workOrderService.update(w);
                    for (String sid : sids)
                    {
                        downloadDao.delete(sid);
                    }
                }
                catch (Exception e)
                {
                    
                }
            }
        });
    }
    
    private void downloadProductImg(String busiId, String[] sids, NeedDownloadFile.Type type)
    {
        
        for (String sid : sids)
        {
            this.threadPool.submit(new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        String imgPath = commonDownLoadFile(sid, type);
                        synchronized (busiId)
                        {
                            Product p = productService.get(Product.class, busiId);
                            String imgs = p.getImg();
                            imgs.replace(sid, imgPath);
                            p.setImg(imgs.replace(sid, imgPath));
                            productService.update(p);
                            downloadDao.delete(sid);
                        }
                    }
                    catch (Exception e)
                    {
                    }
                    
                }
            });
        }
    }
    
    private static abstract class DownTask implements Runnable
    {
        String serverId;
        
        String busiId;
        
        NeedDownloadFile.Type type;
        
        public DownTask(String serverId, String busiId, NeedDownloadFile.Type type)
        {
            super();
            this.serverId = serverId;
            this.busiId = busiId;
            this.type = type;
        }
        
    }
    
    private static class AuthImgDownTask extends DownTask
    {
        
        UpFileDao upFileDao;
        
        NeedDownloadFileDao downloadDao;
        
        public AuthImgDownTask(String serverId, String busiId, NeedDownloadFile.Type type, UpFileDao upFileDao,
            NeedDownloadFileDao downloadDao)
        {
            super(serverId, busiId, type);
            this.upFileDao = upFileDao;
            this.downloadDao = downloadDao;
        }
        
        @Override
        public void run()
        {
            String mpath = "/" + this.type.getPath() + "/" + busiId;
            String savePath = AppContext.getSysParam("wx.upload.save.rootdir") + mpath;
            try
            {
                String fileName = MediaManager.downImg(this.serverId, savePath);
                this.upFileDao.save(new UpFile(this.serverId, mpath + "/" + fileName, type.name()));
                this.downloadDao.delete(serverId);
            }
            catch (Exception e)
            {
                Log.error(
                    "从微信服务器下载素材失败[serverid=" + this.serverId + ",type=" + this.type + ",busiId=" + this.busiId + "]："
                        + e.getMessage(),
                    e);
            }
        }
    }
    
    private SimpleDateFormat YYYYMMDD = new SimpleDateFormat("yyyy/MM/dd");
    
    public String commonDownLoadFile(String serverId, NeedDownloadFile.Type type)
        throws Exception
    {
        String mpath = "/" + type.getPath();
        if (type.isPathAddYYYYmmdd())
        {
            mpath += "/" + YYYYMMDD.format(new Date());
        }
        String savePath = AppContext.getSysParam("wx.upload.save.rootdir") + mpath;
        try
        {
            Log.info("开始从微信公众平台下载serverId=" + serverId);
            String fileName = MediaManager.downImg(serverId, savePath);
            Log.info("完成从微信公众平台下载serverId=" + serverId + " 保存路径：" + mpath + "/" + fileName);
            return mpath + "/" + fileName;
        }
        catch (Exception e)
        {
            Log.error("从微信服务器下载素材失败[serverid=" + serverId + ",type=" + type + "]：" + e.getMessage(), e);
            throw e;
        }
    }
}
