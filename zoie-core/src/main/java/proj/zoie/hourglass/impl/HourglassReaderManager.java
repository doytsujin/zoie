package proj.zoie.hourglass.impl;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import org.apache.lucene.index.AtomicReader;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.SimpleFSDirectory;

import proj.zoie.api.DirectoryManager;
import proj.zoie.api.DocIDMapper;
import proj.zoie.api.ZoieException;
import proj.zoie.api.ZoieIndexReader;
import proj.zoie.api.impl.util.FileUtil;
import proj.zoie.api.indexing.IndexReaderDecorator;
import proj.zoie.impl.indexing.ZoieSystem;

public class HourglassReaderManager<R extends AtomicReader, D>
{
  public static final Logger log = Logger.getLogger(HourglassReaderManager.class.getName());
  private final HourglassDirectoryManagerFactory _dirMgrFactory;
  private final Hourglass<R, D> hg;
  private final IndexReaderDecorator<R> _decorator;
  private volatile Box<R, D> box;
  private volatile boolean isShutdown = false;
  private final Thread maintenanceThread;
  private final ExecutorService retireThreadPool = Executors.newCachedThreadPool();
  private final HourglassListener<R, D> listener;
  private final boolean _appendOnly;
  public HourglassReaderManager(final Hourglass<R, D> hourglass,
                                HourglassDirectoryManagerFactory dirMgrFactory,
                                IndexReaderDecorator<R> decorator,
                                List<ZoieIndexReader<R>> initArchives,
                                List<ZoieSystem<R, D>> initArchiveZoies,
                                List<HourglassListener> hourglassListeners)
  {
    hg = hourglass;
    _dirMgrFactory = dirMgrFactory;
    _appendOnly = _dirMgrFactory.getScheduler().isAppendOnly();
    _decorator = decorator;
    this.listener = new CompositeHourglassListener((List<HourglassListener>)(List)hourglassListeners);

    List<ZoieSystem<R, D>> emptyList = Collections.emptyList();
    
    box = new Box<R, D>(initArchives, initArchiveZoies, emptyList, emptyList, _decorator);
    
    maintenanceThread = new Thread(new Runnable(){
      final int trimThreshold = hourglass._scheduler.getTrimThreshold();

      @Override
      public void run()
      {
        while(true)
        {
          try
          {
        	synchronized(this){
              this.wait(60000);
        	}
          } catch (InterruptedException e)
          {
            log.warn(e);
          }
          List<ZoieIndexReader<R>> archives = new LinkedList<ZoieIndexReader<R>>(box._archives);
          List<ZoieIndexReader<R>> add = new LinkedList<ZoieIndexReader<R>>();
          List<ZoieSystem<R, D>> archiveZoies = new LinkedList<ZoieSystem<R, D>>(box._archiveZoies);
          List<ZoieSystem<R, D>> addZoies = new LinkedList<ZoieSystem<R, D>>();
          try
          {
            hourglass._shutdownLock.readLock().lock();
            if (isShutdown)
            {
              log.info("Already shut down. Quiting maintenance thread.");
              break;
            }
            if (_appendOnly)
            {
              if (archives.size() > trimThreshold)
              { 
                log.info("to maintain");
              } else continue;
              trim(archives);
              // swap the archive with consolidated one
              swapArchives(archives, add);
            }
            else
            {
              if (archiveZoies.size() > trimThreshold)
              { 
                log.info("to maintain");
              } else continue;
              trimZoie(archiveZoies);
              // swap the archive with consolidated one
              swapArchiveZoies(archiveZoies, addZoies);
            }
          } finally
          {
            hourglass._shutdownLock.readLock().unlock();
          }
        }
      }},"HourglassReaderManager Zoie Maintenanace Thread");
    maintenanceThread.start();
  }

  /**
   * consolidate the archived Index to one big optimized index and put in add
   * @param toRemove
   * @param add
   */
  private void trimZoie(List<ZoieSystem<R, D>> toRemove)
  {
    long timenow = System.currentTimeMillis();
    List<ZoieSystem<R, D>> toKeep = new LinkedList<ZoieSystem<R, D>>();
    Calendar now = Calendar.getInstance();
    now.setTimeInMillis(timenow);
    Calendar threshold = hg._scheduler.getTrimTime(now);

    List<ZoieSystem<R, D>> zoieList = new ArrayList<ZoieSystem<R, D>>(toRemove);
    Collections.sort(zoieList, new Comparator<ZoieSystem<R, D>>()
                {
                  @Override
                  public int compare(ZoieSystem<R, D> r1, ZoieSystem<R, D> r2)
                  {
                    String name1 = r1.getIndexDir();
                    String name2 = r2.getIndexDir();
                    return name2.compareTo(name1);
                  }
                });

    boolean foundOldestToKeep = false;

    for (ZoieSystem<R, D> zoie: zoieList)
    {
      File dir = new File(zoie.getIndexDir());
      String path = dir.getName();

      if (foundOldestToKeep)
      {
        if (listener != null) {
          List<ZoieIndexReader<R>> readers = null;
          try
          {
            readers = zoie.getIndexReaders();
            if (readers != null)
            {
              for (ZoieIndexReader reader : readers)
                listener.onIndexReaderCleanUp(reader);
            }
          }
          catch(Exception e)
          {
            log.error("Error happend on reader cleanup", e);
          }
          finally
          {
            if (readers != null)
              zoie.returnIndexReaders(readers);
          }
        }
        zoie.shutdown();
        log.info("trimming: remove " + path);
        log.info(path + " -before-- delete");
        FileUtil.rmDir(dir);
        log.info(path + " -after-- delete");
        continue;
      }
      else
      {
        // Always keep this zoie (when the oldest one to keep has not
        // been found), no matter the index directory name can be parsed
        // or not.
        toKeep.add(zoie);

        Calendar archivetime = null;
        try
        {
          archivetime = HourglassDirectoryManagerFactory.getCalendarTime(path);
        }
        catch (ParseException e)
        {
          log.error("index directory name bad. potential corruption. Move on without trimming.", e);
          continue;
        }

        if (archivetime.before(threshold))
        {
          foundOldestToKeep = true;
        }
      }
    }
    toRemove.removeAll(toKeep);
  }

  /**
   * consolidate the archived Index to one big optimized index and put in add
   * @param toRemove
   * @param add
   */
  private void trim(List<ZoieIndexReader<R>> toRemove)
  {
    long timenow = System.currentTimeMillis();
    List<ZoieIndexReader<R>> toKeep = new LinkedList<ZoieIndexReader<R>>();
    Calendar now = Calendar.getInstance();
    now.setTimeInMillis(timenow);
    Calendar threshold = hg._scheduler.getTrimTime(now);

    //ZoieIndexReader<R>[] readerArray = toRemove.toArray(new ZoieIndexReader[toRemove.size()]);
    List<ZoieIndexReader<R>> readerList = new ArrayList<ZoieIndexReader<R>>(toRemove);
    Collections.sort(readerList, new Comparator<ZoieIndexReader<R>>()
                {
                  @Override
                  public int compare(ZoieIndexReader<R> r1, ZoieIndexReader<R> r2)
                  {
                    String name1 = ((SimpleFSDirectory) r1.directory()).getDirectory().getName();
                    String name2 = ((SimpleFSDirectory) r2.directory()).getDirectory().getName();
                    return name2.compareTo(name1);
                  }
                });

    boolean foundOldestToKeep = false;

    for (ZoieIndexReader<R> reader: readerList)
    {
      SimpleFSDirectory dir = (SimpleFSDirectory) reader.directory();
      String path = dir.getDirectory().getName();

      if (foundOldestToKeep)
      {
        if (listener != null) {
          listener.onIndexReaderCleanUp(reader);
        }
        log.info("trimming: remove " + path);
        log.info(dir.getDirectory() + " -before--" + (dir.getDirectory().exists()?" not deleted ":" deleted"));
        FileUtil.rmDir(dir.getDirectory());
        log.info(dir.getDirectory() + " -after--" + (dir.getDirectory().exists()?" not deleted ":" deleted"));
        continue;
      }
      else
      {
        // Always keep this reader (when the oldest one to keep has not
        // been found), no matter the index directory name can be parsed
        // or not.
        toKeep.add(reader);

        Calendar archivetime = null;
        try
        {
          archivetime = HourglassDirectoryManagerFactory.getCalendarTime(path);
        }
        catch (ParseException e)
        {
          log.error("index directory name bad. potential corruption. Move on without trimming.", e);
          continue;
        }

        if (archivetime.before(threshold))
        {
          foundOldestToKeep = true;
        }
      }
    }
    toRemove.removeAll(toKeep);
  }
 
  /**
   * Remove and add should be <b>disjoint</b>
   * @param remove the zoies to be remove. This has to be disjoint from add.
   * @param add
   */
  public synchronized void swapArchiveZoies(List<ZoieSystem<R, D>> remove, List<ZoieSystem<R, D>> add)
  {
    List<ZoieSystem<R, D>> archives = new LinkedList<ZoieSystem<R, D>>(add);
    if (!box._archiveZoies.containsAll(remove))
    {
      log.error("swapArchiveZoies: potential sync issue. ");
    }
    archives.addAll(box._archiveZoies);
    archives.removeAll(remove);
    Box<R, D> newbox = new Box<R, D>(box._archives, archives, box._retiree, box._actives, _decorator);
    box = newbox;
  }

  /**
   * The readers removed will also be decRef(). But the readers to be added will NOT get incRef(),
   * which means we assume the newly added ones have already been incRef().
   * remove and add should be <b>disjoint</b>
   * @param remove the readers to be remove. This has to be disjoint from add.
   * @param add
   */
  public synchronized void swapArchives(List<ZoieIndexReader<R>> remove, List<ZoieIndexReader<R>> add)
  {
    List<ZoieIndexReader<R>> archives = new LinkedList<ZoieIndexReader<R>>(add);
    if (!box._archives.containsAll(remove))
    {
      log.error("swapArchives: potential sync issue. ");
    }
    archives.addAll(box._archives);
    archives.removeAll(remove);
    for(ZoieIndexReader<R> r : remove)
    {
      r.decZoieRef();
      if (log.isDebugEnabled())
      {
        log.debug("remove time " + r.directory() + " refCount: " + r.getRefCount());
      }
    }
    Box<R, D> newbox = new Box<R, D>(archives, box._archiveZoies, box._retiree, box._actives, _decorator);
    box = newbox;
  }

  public synchronized ZoieSystem<R, D> retireAndNew(final ZoieSystem<R, D> old)
  {
    DirectoryManager _dirMgr = _dirMgrFactory.getDirectoryManager();
    _dirMgrFactory.clearRecentlyChanged();
    ZoieSystem<R, D> newzoie = hg.createZoie(_dirMgr);
    List<ZoieSystem<R, D>> actives = new LinkedList<ZoieSystem<R, D>>(box._actives);
    List<ZoieSystem<R, D>> retiring = new LinkedList<ZoieSystem<R, D>>(box._retiree);
    if (old!=null)
    {
      actives.remove(old);
      retiring.add(old);
      retireThreadPool.execute(new Runnable()
      {
        @Override
        public void run()
        {
          if (listener != null) {
            listener.onRetiredZoie(old);
          }
          retire(old);
        }});
    }
    actives.add(newzoie);
    Box<R, D> newbox = new Box<R, D>(box._archives, box._archiveZoies, retiring, actives, _decorator);
    box = newbox;
    if (listener != null) {
      listener.onNewZoie(newzoie);
    }
    return newzoie;
  }
  /**
   * @param zoie
   * @param reader the IndexReader opened on the index the give zoie had written to.
   */
  public synchronized void archive(ZoieSystem<R, D> zoie, ZoieIndexReader<R> reader)
  {
    List<ZoieIndexReader<R>> archives = new LinkedList<ZoieIndexReader<R>>(box._archives);
    List<ZoieSystem<R, D>> archiveZoies = new LinkedList<ZoieSystem<R, D>>(box._archiveZoies);
    List<ZoieSystem<R, D>> actives = new LinkedList<ZoieSystem<R, D>>(box._actives);
    List<ZoieSystem<R, D>> retiring = new LinkedList<ZoieSystem<R, D>>(box._retiree);

    retiring.remove(zoie);
    if (!_appendOnly)
      archiveZoies.add(zoie);

    if (reader != null)
    {
      archives.add(reader);
    }
    Box<R, D> newbox = new Box<R, D>(archives, archiveZoies, retiring, actives, _decorator);
    box = newbox;
  }
  private synchronized void preshutdown()
  {
    log.info("shutting down thread pool.");
    isShutdown = true;
    synchronized(maintenanceThread){
      maintenanceThread.notifyAll();
    }
    try {
		maintenanceThread.join(10000);
	} catch (InterruptedException e) {
		log.info("Maintenance thread interrpted");
	}
    retireThreadPool.shutdown();
  }
  public void shutdown()
  {
    preshutdown();
    while(true)
    {
      TimeUnit unit=TimeUnit.SECONDS;
      long t=10L;
      try
      {
        if (retireThreadPool.awaitTermination(t, unit)) break;
      } catch (InterruptedException e)
      {
        log.warn("Exception when trying to shutdown. Will retry.", e);
      }
    }
    log.info("shutting down thread pool complete.");
    log.info("shutting down indices.");
    box.shutdown();
    log.info("shutting down indices complete.");
  }
  public synchronized List<ZoieIndexReader<R>> getIndexReaders() throws IOException
  {
    List<ZoieIndexReader<R>> list = new ArrayList<ZoieIndexReader<R>>();
    if (_appendOnly)
    {
      // add the archived index readers.
      for(ZoieIndexReader<R> r : box._archives)
      {
       if (log.isDebugEnabled()){
        log.debug("add reader from box archives");
        }
        r.incZoieRef();
        list.add(r);
      }
    }
    else
    {
      // add the archived readers from zoie.
      for(ZoieSystem<R, D> zoie : box._archiveZoies)
      {
        if (log.isDebugEnabled()){
          log.debug("add reader from box archiveZoies");
        }
        list.addAll(zoie.getIndexReaders());
      }
    }
    // add the retiring index readers
    for(ZoieSystem<R, D> zoie : box._retiree)
    {

      if (log.isDebugEnabled()){
   	    log.debug("add reader from box retiree");
      }
      list.addAll(zoie.getIndexReaders());
    }
    // add the active index readers
    for(ZoieSystem<R, D> zoie : box._actives)
    {

      if (log.isDebugEnabled()){
     	 log.debug("add reader from box actvies");
      }
      list.addAll(zoie.getIndexReaders());
    }

    if (log.isDebugEnabled()){
   	 log.debug("returning reader of size: "+list.size());
    }
    return list;
  }  
  protected void retire(ZoieSystem<R, D> zoie)
  {
    long t0 = System.currentTimeMillis();
    log.info("retiring " + zoie.getAdminMBean().getIndexDir());
    while(true)
    {
      long flushwait = 200000L;
      try
      {
        zoie.flushEvents(flushwait);
        zoie.getAdminMBean().setUseCompoundFile(true);
        zoie.getAdminMBean().optimize(1);
        break;
      } catch (IOException e)
      {
        log.error("retiring " + zoie.getAdminMBean().getIndexDir() + " Should investigate. But move on now.", e);
        break;
      } catch (ZoieException e)
      {
        if (e.getMessage().indexOf("timed out")<0)
        {
          break;
        } else
        {
          log.info("retiring " + zoie.getAdminMBean().getIndexDir() + " flushing processing " + flushwait +"ms elapsed");
        }
      }
    }
    ZoieIndexReader<R> zoiereader = null;

    if (_appendOnly)
    {
      IndexReader reader = null;
      try
      {
        reader = getArchive(zoie);
      } catch (CorruptIndexException e)
      {
        log.error("retiring " + zoie.getAdminMBean().getIndexDir() + " Should investigate. But move on now.", e);
      } catch (IOException e)
      {
        log.error("retiring " + zoie.getAdminMBean().getIndexDir() + " Should investigate. But move on now.", e);
      }
      try
      {
        zoiereader = new ZoieIndexReader<R>(reader, _decorator);

        // Initialize docIdMapper
        DocIDMapper<?> mapper = hg.getzConfig().getDocidMapperFactory().getDocIDMapper(zoiereader);
        zoiereader.setDocIDMapper(mapper);
      } catch (IOException e)
      {
        log.error(e);
      }
    }
    archive(zoie, zoiereader);
    log.info("retired " + zoie.getAdminMBean().getIndexDir() + " in " + (System.currentTimeMillis()-t0)+"ms");
    log.info("Disk Index Size Total Now: " + (hg.getSizeBytes()/1024L) + "KB");
    if (_appendOnly) zoie.shutdown();
  }

  public List<ZoieSystem<R, D>> getArchiveZoies()
  {
    return box._archiveZoies;
  }

  private IndexReader getArchive(ZoieSystem<R, D> zoie) throws CorruptIndexException, IOException
  {
    String dirName = zoie.getAdminMBean().getIndexDir();
    Directory dir = new SimpleFSDirectory(new File(dirName));
    IndexReader reader = null;
    if (IndexReader.indexExists(dir))
    {
      reader  = IndexReader.open(dir, true);
    }
    else
    {
      log.info("empty index " + dirName);
      reader = null;
    }
    return reader;
  }
}
