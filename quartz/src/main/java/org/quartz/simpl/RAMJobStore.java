/* 
 * Copyright 2001-2009 Terracotta, Inc. 
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not 
 * use this file except in compliance with the License. You may obtain a copy 
 * of the License at 
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0 
 *   
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT 
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the 
 * License for the specific language governing permissions and limitations 
 * under the License.
 * 
 */

package org.quartz.simpl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

import org.quartz.Calendar;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.JobPersistenceException;
import org.quartz.ObjectAlreadyExistsException;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.Trigger.TriggerState;
import org.quartz.impl.JobDetailImpl;
import org.quartz.spi.ClassLoadHelper;
import org.quartz.spi.JobStore;
import org.quartz.spi.OperableTrigger;
import org.quartz.spi.SchedulerSignaler;
import org.quartz.spi.TriggerFiredBundle;
import org.quartz.spi.TriggerFiredResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * This class implements a <code>{@link org.quartz.spi.JobStore}</code> that
 * utilizes RAM as its storage device.
 * </p>
 * 
 * <p>
 * As you should know, the ramification of this is that access is extrememly
 * fast, but the data is completely volatile - therefore this <code>JobStore</code>
 * should not be used if true persistence between program shutdowns is
 * required.
 * </p>
 * 
 * @author James House
 * @author Sharada Jambula
 * @author Eric Mueller
 */
public class RAMJobStore implements JobStore {

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * Data members.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    protected HashMap<JobKey, JobWrapper> jobsByKey = new HashMap<JobKey, JobWrapper>(1000);

    protected HashMap<TriggerKey, TriggerWrapper> triggersByKey = new HashMap<TriggerKey, TriggerWrapper>(1000);

    protected HashMap<String, HashMap<JobKey, JobWrapper>> jobsByGroup = new HashMap<String, HashMap<JobKey, JobWrapper>>(25);

    protected HashMap<String, HashMap<TriggerKey, TriggerWrapper>> triggersByGroup = new HashMap<String, HashMap<TriggerKey, TriggerWrapper>>(25);

    protected TreeSet<TriggerWrapper> timeTriggers = new TreeSet<TriggerWrapper>(new TriggerComparator());

    protected HashMap<String, Calendar> calendarsByName = new HashMap<String, Calendar>(25);

    protected ArrayList<TriggerWrapper> triggers = new ArrayList<TriggerWrapper>(1000);

    protected final Object lock = new Object();

    protected HashSet<String> pausedTriggerGroups = new HashSet<String>();

    protected HashSet<String> pausedJobGroups = new HashSet<String>();

    protected HashSet<JobKey> blockedJobs = new HashSet<JobKey>();
    
    protected long misfireThreshold = 5000l;

    protected SchedulerSignaler signaler;

    private final Logger log = LoggerFactory.getLogger(getClass());

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * Constructors.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    /**
     * <p>
     * Create a new <code>RAMJobStore</code>.
     * </p>
     */
    public RAMJobStore() {
    }

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * Interface.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    protected Logger getLog() {
        return log;
    }

    /**
     * <p>
     * Called by the QuartzScheduler before the <code>JobStore</code> is
     * used, in order to give the it a chance to initialize.
     * </p>
     */
    public void initialize(ClassLoadHelper loadHelper,
            SchedulerSignaler signaler) {

        this.signaler = signaler;

        getLog().info("RAMJobStore initialized.");
    }

    public void schedulerStarted() throws SchedulerException {
        // nothing to do
    }

    public long getMisfireThreshold() {
        return misfireThreshold;
    }

    /**
     * The number of milliseconds by which a trigger must have missed its
     * next-fire-time, in order for it to be considered "misfired" and thus
     * have its misfire instruction applied.
     * 
     * @param misfireThreshold
     */
    public void setMisfireThreshold(long misfireThreshold) {
        if (misfireThreshold < 1) {
            throw new IllegalArgumentException("Misfirethreashold must be larger than 0");
        }
        this.misfireThreshold = misfireThreshold;
    }

    /**
     * <p>
     * Called by the QuartzScheduler to inform the <code>JobStore</code> that
     * it should free up all of it's resources because the scheduler is
     * shutting down.
     * </p>
     */
    public void shutdown() {
    }

    public boolean supportsPersistence() {
        return false;
    }

    /**
     * Clear (delete!) all scheduling data - all {@link Job}s, {@link Trigger}s
     * {@link Calendar}s.
     * 
     * @throws JobPersistenceException
     */
    public void clearAllSchedulingData() throws JobPersistenceException {

        synchronized (lock) {
            // unschedule jobs (delete triggers)
            List<String> lst = getTriggerGroupNames();
            for (String group: lst) {
                List<TriggerKey> keys = getTriggerKeys(group);
                for (TriggerKey key: keys) {
                    removeTrigger(key);
                }
            }
            // delete jobs
            lst = getJobGroupNames();
            for (String group: lst) {
                List<JobKey> keys = getJobKeys(group);
                for (JobKey key: keys) {
                    removeJob(key);
                }
            }
            // delete calendars
            lst = getCalendarNames();
            for(String name: lst) {
                removeCalendar(name);
            }
        }
    }
    
    /**
     * <p>
     * Store the given <code>{@link org.quartz.JobDetail}</code> and <code>{@link org.quartz.Trigger}</code>.
     * </p>
     * 
     * @param newJob
     *          The <code>JobDetail</code> to be stored.
     * @param newTrigger
     *          The <code>Trigger</code> to be stored.
     * @throws ObjectAlreadyExistsException
     *           if a <code>Job</code> with the same name/group already
     *           exists.
     */
    public void storeJobAndTrigger(JobDetail newJob,
            OperableTrigger newTrigger) throws JobPersistenceException {
        storeJob(newJob, false);
        storeTrigger(newTrigger, false);
    }

    /**
     * <p>
     * Store the given <code>{@link org.quartz.Job}</code>.
     * </p>
     * 
     * @param newJob
     *          The <code>Job</code> to be stored.
     * @param replaceExisting
     *          If <code>true</code>, any <code>Job</code> existing in the
     *          <code>JobStore</code> with the same name & group should be
     *          over-written.
     * @throws ObjectAlreadyExistsException
     *           if a <code>Job</code> with the same name/group already
     *           exists, and replaceExisting is set to false.
     */
    public void storeJob(JobDetail newJob,
            boolean replaceExisting) throws ObjectAlreadyExistsException {
        JobWrapper jw = new JobWrapper((JobDetail)newJob.clone());

        boolean repl = false;

        synchronized (lock) {
            if (jobsByKey.get(jw.key) != null) {
                if (!replaceExisting) {
                    throw new ObjectAlreadyExistsException(newJob);
                }
                repl = true;
            }

            if (!repl) {
                // get job group
                HashMap<JobKey, JobWrapper> grpMap = jobsByGroup.get(newJob.getKey().getGroup());
                if (grpMap == null) {
                    grpMap = new HashMap(100);
                    jobsByGroup.put(newJob.getKey().getGroup(), grpMap);
                }
                // add to jobs by group
                grpMap.put(newJob.getKey(), jw);
                // add to jobs by FQN map
                jobsByKey.put(jw.key, jw);
            } else {
                // update job detail
                JobWrapper orig = (JobWrapper) jobsByKey.get(jw.key);
                orig.jobDetail = jw.jobDetail; // already cloned
            }
        }
    }

    /**
     * <p>
     * Remove (delete) the <code>{@link org.quartz.Job}</code> with the given
     * name, and any <code>{@link org.quartz.Trigger}</code> s that reference
     * it.
     * </p>
     *
     * @return <code>true</code> if a <code>Job</code> with the given name &
     *         group was found and removed from the store.
     */
    public boolean removeJob(JobKey jobKey) {

        boolean found = false;

        synchronized (lock) {
            List<OperableTrigger> triggers = getTriggersForJob(jobKey);
            for (OperableTrigger trig: triggers) {
                this.removeTrigger(trig.getKey());
                found = true;
            }
            
            found = (jobsByKey.remove(jobKey) != null) | found;
            if (found) {

                HashMap<JobKey, JobWrapper> grpMap = jobsByGroup.get(jobKey.getGroup());
                if (grpMap != null) {
                    grpMap.remove(jobKey);
                    if (grpMap.size() == 0) {
                        jobsByGroup.remove(jobKey.getGroup());
                    }
                }
            }
        }

        return found;
    }

    public boolean removeJobs(List<JobKey> jobKeys)
            throws JobPersistenceException {
        boolean allFound = true;

        synchronized (lock) {
            for(JobKey key: jobKeys)
                allFound = removeJob(key) && allFound;
        }

        return allFound;
    }

    public boolean removeTriggers(List<TriggerKey> triggerKeys)
            throws JobPersistenceException {
        boolean allFound = true;

        synchronized (lock) {
            for(TriggerKey key: triggerKeys)
                allFound = removeTrigger(key) && allFound;
        }

        return allFound;
    }

    public void storeJobsAndTriggers(
            Map<JobDetail, List<Trigger>> triggersAndJobs, boolean replace)
            throws ObjectAlreadyExistsException, JobPersistenceException {

        synchronized (lock) {
            // make sure there are no collisions...
            if(!replace) {
                for(JobDetail job: triggersAndJobs.keySet()) {
                    if(checkExists(job.getKey()))
                        throw new ObjectAlreadyExistsException(job);
                    for(Trigger trigger: triggersAndJobs.get(job)) {
                        if(checkExists(trigger.getKey()))
                            throw new ObjectAlreadyExistsException(trigger);
                    }
                }
            }
            // do bulk add...
            for(JobDetail job: triggersAndJobs.keySet()) {
                storeJob(job, true);
                for(Trigger trigger: triggersAndJobs.get(job)) {
                    storeTrigger((OperableTrigger) trigger, true);
                }
            }
        }
        
    }

    /**
     * <p>
     * Store the given <code>{@link org.quartz.Trigger}</code>.
     * </p>
     *
     * @param newTrigger
     *          The <code>Trigger</code> to be stored.
     * @param replaceExisting
     *          If <code>true</code>, any <code>Trigger</code> existing in
     *          the <code>JobStore</code> with the same name & group should
     *          be over-written.
     * @throws ObjectAlreadyExistsException
     *           if a <code>Trigger</code> with the same name/group already
     *           exists, and replaceExisting is set to false.
     *
     * @see #pauseTriggerGroup(SchedulingContext, String)
     */
    public void storeTrigger(OperableTrigger newTrigger,
            boolean replaceExisting) throws JobPersistenceException {
        TriggerWrapper tw = new TriggerWrapper((OperableTrigger)newTrigger.clone());

        synchronized (lock) {
            if (triggersByKey.get(tw.key) != null) {
                if (!replaceExisting) {
                    throw new ObjectAlreadyExistsException(newTrigger);
                }
    
                removeTrigger(newTrigger.getKey(), false);
            }
    
            if (retrieveJob(newTrigger.getJobKey()) == null) {
                throw new JobPersistenceException("The job ("
                        + newTrigger.getJobKey()
                        + ") referenced by the trigger does not exist.");
            }

            // add to triggers array
            triggers.add(tw);
            // add to triggers by group
            HashMap<TriggerKey, TriggerWrapper> grpMap = (HashMap) triggersByGroup.get(newTrigger.getKey().getGroup());
            if (grpMap == null) {
                grpMap = new HashMap<TriggerKey, TriggerWrapper>(100);
                triggersByGroup.put(newTrigger.getKey().getGroup(), grpMap);
            }
            grpMap.put(newTrigger.getKey(), tw);
            // add to triggers by FQN map
            triggersByKey.put(tw.key, tw);

            if (pausedTriggerGroups.contains(newTrigger.getKey().getGroup())
            		|| pausedJobGroups.contains(newTrigger.getJobKey().getGroup())) {
                tw.state = TriggerWrapper.STATE_PAUSED;
                if (blockedJobs.contains(tw.jobKey)) {
                    tw.state = TriggerWrapper.STATE_PAUSED_BLOCKED;
                }
            } else if (blockedJobs.contains(tw.jobKey)) {
                tw.state = TriggerWrapper.STATE_BLOCKED;
            } else {
                timeTriggers.add(tw);
            }
        }
    }

    /**
     * <p>
     * Remove (delete) the <code>{@link org.quartz.Trigger}</code> with the
     * given name.
     * </p>
     *
     * @return <code>true</code> if a <code>Trigger</code> with the given
     *         name & group was found and removed from the store.
     */
    public boolean removeTrigger(TriggerKey triggerKey) {
        return removeTrigger(triggerKey, true);
    }
    
    private boolean removeTrigger(TriggerKey key, boolean removeOrphanedJob) {

        boolean found = false;

        synchronized (lock) {
            // remove from triggers by FQN map
            found = (triggersByKey.remove(key) == null) ? false : true;
            if (found) {
                TriggerWrapper tw = null;
                // remove from triggers by group
                HashMap grpMap = (HashMap) triggersByGroup.get(key.getGroup());
                if (grpMap != null) {
                    grpMap.remove(key);
                    if (grpMap.size() == 0) {
                        triggersByGroup.remove(key.getGroup());
                    }
                }
                // remove from triggers array
                Iterator tgs = triggers.iterator();
                while (tgs.hasNext()) {
                    tw = (TriggerWrapper) tgs.next();
                    if (key.equals(tw.key)) {
                        tgs.remove();
                        break;
                    }
                }
                timeTriggers.remove(tw);

                if (removeOrphanedJob) {
                    JobWrapper jw = (JobWrapper) jobsByKey.get(tw.jobKey);
                    List<OperableTrigger> trigs = getTriggersForJob(tw.jobKey);
                    if ((trigs == null || trigs.size() == 0) && !jw.jobDetail.isDurable()) {
                        if (removeJob(jw.key)) {
                            signaler.notifySchedulerListenersJobDeleted(jw.key);
                        }
                    }
                }
            }
        }

        return found;
    }


    /**
     * @see org.quartz.spi.JobStore#replaceTrigger(org.quartz.core.SchedulingContext, java.lang.String, java.lang.String, org.quartz.Trigger)
     */
    public boolean replaceTrigger(TriggerKey triggerKey, OperableTrigger newTrigger) throws JobPersistenceException {

        boolean found = false;

        synchronized (lock) {
            // remove from triggers by FQN map
            TriggerWrapper tw = (TriggerWrapper) triggersByKey.remove(triggerKey);
            found = ( tw == null) ? false : true;

            if (found) {

                if (!tw.getTrigger().getJobKey().equals(newTrigger.getJobKey())) {
                    throw new JobPersistenceException("New trigger is not related to the same job as the old trigger.");
                }

                tw = null;
                // remove from triggers by group
                HashMap<TriggerKey, TriggerWrapper> grpMap = (HashMap) triggersByGroup.get(triggerKey.getGroup());
                if (grpMap != null) {
                    grpMap.remove(triggerKey);
                    if (grpMap.size() == 0) {
                        triggersByGroup.remove(triggerKey.getGroup());
                    }
                }
                // remove from triggers array
                Iterator tgs = triggers.iterator();
                while (tgs.hasNext()) {
                    tw = (TriggerWrapper) tgs.next();
                    if (triggerKey.equals(tw.key)) {
                        tgs.remove();
                        break;
                    }
                }
                timeTriggers.remove(tw);

                try {
                    storeTrigger(newTrigger, false);
                } catch(JobPersistenceException jpe) {
                    storeTrigger(tw.getTrigger(), false); // put previous trigger back...
                    throw jpe;
                }
            }
        }

        return found;
    }

    /**
     * <p>
     * Retrieve the <code>{@link org.quartz.JobDetail}</code> for the given
     * <code>{@link org.quartz.Job}</code>.
     * </p>
     *
     * @return The desired <code>Job</code>, or null if there is no match.
     */
    public JobDetail retrieveJob(JobKey jobKey) {
        synchronized(lock) {
            JobWrapper jw = (JobWrapper) jobsByKey.get(jobKey);
            return (jw != null) ? (JobDetail)jw.jobDetail.clone() : null;
        }
    }

    /**
     * <p>
     * Retrieve the given <code>{@link org.quartz.Trigger}</code>.
     * </p>
     *
     * @return The desired <code>Trigger</code>, or null if there is no
     *         match.
     */
    public OperableTrigger retrieveTrigger(TriggerKey triggerKey) {
        synchronized(lock) {
            TriggerWrapper tw = (TriggerWrapper) triggersByKey.get(triggerKey);
    
            return (tw != null) ? (OperableTrigger)tw.getTrigger().clone() : null;
        }
    }
    
    /**
     * Determine whether a {@link Job} with the given identifier already 
     * exists within the scheduler.
     * 
     * @param jobKey the identifier to check for
     * @return true if a Job exists with the given identifier
     * @throws SchedulerException 
     */
    public boolean checkExists(JobKey jobKey)  {
        synchronized(lock) {
            JobWrapper jw = (JobWrapper) jobsByKey.get(jobKey);
            return (jw != null);
        }
    }
    
    /**
     * Determine whether a {@link Trigger} with the given identifier already 
     * exists within the scheduler.
     * 
     * @param triggerKey the identifier to check for
     * @return true if a Trigger exists with the given identifier
     * @throws SchedulerException 
     */
    public boolean checkExists(TriggerKey triggerKey) {
        synchronized(lock) {
            TriggerWrapper tw = (TriggerWrapper) triggersByKey.get(triggerKey);
    
            return (tw != null);
        }
    }
 
    /**
     * <p>
     * Get the current state of the identified <code>{@link Trigger}</code>.
     * </p>
     *
     * @see Trigger#STATE_NORMAL
     * @see Trigger#STATE_PAUSED
     * @see Trigger#STATE_COMPLETE
     * @see Trigger#STATE_ERROR
     * @see Trigger#STATE_BLOCKED
     * @see Trigger#STATE_NONE
     */
    public TriggerState getTriggerState(TriggerKey triggerKey) throws JobPersistenceException {
        synchronized(lock) {
            TriggerWrapper tw = (TriggerWrapper) triggersByKey.get(triggerKey);
            
            if (tw == null) {
                return TriggerState.STATE_NONE;
            }
    
            if (tw.state == TriggerWrapper.STATE_COMPLETE) {
                return TriggerState.STATE_COMPLETE;
            }
    
            if (tw.state == TriggerWrapper.STATE_PAUSED) {
                return TriggerState.STATE_PAUSED;
            }
    
            if (tw.state == TriggerWrapper.STATE_PAUSED_BLOCKED) {
                return TriggerState.STATE_PAUSED;
            }
    
            if (tw.state == TriggerWrapper.STATE_BLOCKED) {
                return TriggerState.STATE_BLOCKED;
            }
    
            if (tw.state == TriggerWrapper.STATE_ERROR) {
                return TriggerState.STATE_ERROR;
            }
    
            return TriggerState.STATE_NORMAL;
        }
    }

    /**
     * <p>
     * Store the given <code>{@link org.quartz.Calendar}</code>.
     * </p>
     *
     * @param calendar
     *          The <code>Calendar</code> to be stored.
     * @param replaceExisting
     *          If <code>true</code>, any <code>Calendar</code> existing
     *          in the <code>JobStore</code> with the same name & group
     *          should be over-written.
     * @param updateTriggers
     *          If <code>true</code>, any <code>Trigger</code>s existing
     *          in the <code>JobStore</code> that reference an existing
     *          Calendar with the same name with have their next fire time
     *          re-computed with the new <code>Calendar</code>.
     * @throws ObjectAlreadyExistsException
     *           if a <code>Calendar</code> with the same name already
     *           exists, and replaceExisting is set to false.
     */
    public void storeCalendar(String name,
            Calendar calendar, boolean replaceExisting, boolean updateTriggers)
        throws ObjectAlreadyExistsException {

        calendar = (Calendar) calendar.clone();
        
        synchronized (lock) {
    
            Object obj = calendarsByName.get(name);
    
            if (obj != null && replaceExisting == false) {
                throw new ObjectAlreadyExistsException(
                    "Calendar with name '" + name + "' already exists.");
            } else if (obj != null) {
                calendarsByName.remove(name);
            }
    
            calendarsByName.put(name, calendar);
    
            if(obj != null && updateTriggers) {
                Iterator trigs = getTriggerWrappersForCalendar(name).iterator();
                while (trigs.hasNext()) {
                    TriggerWrapper tw = (TriggerWrapper) trigs.next();
                    OperableTrigger trig = tw.getTrigger();
                    boolean removed = timeTriggers.remove(tw);

                    trig.updateWithNewCalendar(calendar, getMisfireThreshold());

                    if(removed) {
                        timeTriggers.add(tw);
                    }
                }
            }
        }
    }

    /**
     * <p>
     * Remove (delete) the <code>{@link org.quartz.Calendar}</code> with the
     * given name.
     * </p>
     *
     * <p>
     * If removal of the <code>Calendar</code> would result in
     * <code>Trigger</code>s pointing to non-existent calendars, then a
     * <code>JobPersistenceException</code> will be thrown.</p>
     *       *
     * @param calName The name of the <code>Calendar</code> to be removed.
     * @return <code>true</code> if a <code>Calendar</code> with the given name
     * was found and removed from the store.
     */
    public boolean removeCalendar(String calName)
        throws JobPersistenceException {
        int numRefs = 0;

        synchronized (lock) {
            Iterator itr = triggers.iterator();
            while (itr.hasNext()) {
                OperableTrigger trigg = ((TriggerWrapper) itr.next()).trigger;
                if (trigg.getCalendarName() != null
                        && trigg.getCalendarName().equals(calName)) {
                    numRefs++;
                }
            }
        }

        if (numRefs > 0) {
            throw new JobPersistenceException(
                    "Calender cannot be removed if it referenced by a Trigger!");
        }

        return (calendarsByName.remove(calName) != null);
    }

    /**
     * <p>
     * Retrieve the given <code>{@link org.quartz.Trigger}</code>.
     * </p>
     *
     * @param calName
     *          The name of the <code>Calendar</code> to be retrieved.
     * @return The desired <code>Calendar</code>, or null if there is no
     *         match.
     */
    public Calendar retrieveCalendar(String calName) {
        synchronized (lock) {
            Calendar cal = (Calendar) calendarsByName.get(calName);
            if(cal != null)
                return (Calendar) cal.clone();
            return null;
        }
    }

    /**
     * <p>
     * Get the number of <code>{@link org.quartz.JobDetail}</code> s that are
     * stored in the <code>JobsStore</code>.
     * </p>
     */
    public int getNumberOfJobs() {
        synchronized (lock) {
            return jobsByKey.size();
        }
    }

    /**
     * <p>
     * Get the number of <code>{@link org.quartz.Trigger}</code> s that are
     * stored in the <code>JobsStore</code>.
     * </p>
     */
    public int getNumberOfTriggers() {
        synchronized (lock) {
            return triggers.size();
        }
    }

    /**
     * <p>
     * Get the number of <code>{@link org.quartz.Calendar}</code> s that are
     * stored in the <code>JobsStore</code>.
     * </p>
     */
    public int getNumberOfCalendars() {
        synchronized (lock) {
            return calendarsByName.size();
        }
    }

    /**
     * <p>
     * Get the names of all of the <code>{@link org.quartz.Job}</code> s that
     * have the given group name.
     * </p>
     */
    public List<JobKey> getJobKeys(String groupName) {
        List<JobKey> outList = null;
        synchronized (lock) {
            HashMap<JobKey, JobWrapper> grpMap = jobsByGroup.get(groupName);
            if (grpMap != null) {
                outList = new LinkedList<JobKey>();

                for (JobWrapper jw : grpMap.values()) {

                    if (jw != null) {
                        outList.add(jw.jobDetail.getKey());
                    }
                }
            } else {
                outList = java.util.Collections.emptyList();
            }
        }

        return outList;
    }

    /**
     * <p>
     * Get the names of all of the <code>{@link org.quartz.Calendar}</code> s
     * in the <code>JobStore</code>.
     * </p>
     *
     * <p>
     * If there are no Calendars in the given group name, the result should be
     * a zero-length array (not <code>null</code>).
     * </p>
     */
    public List<String> getCalendarNames() {
        synchronized(lock) {
            return new LinkedList<String>(calendarsByName.keySet());
        }
    }

    /**
     * <p>
     * Get the names of all of the <code>{@link org.quartz.Trigger}</code> s
     * that have the given group name.
     * </p>
     */
    public List<TriggerKey> getTriggerKeys(String groupName) {
        List<TriggerKey> outList = null;
        synchronized (lock) {
            HashMap<TriggerKey, TriggerWrapper> grpMap = triggersByGroup.get(groupName);
            if (grpMap != null) {
                outList = new LinkedList<TriggerKey>();

                for(TriggerWrapper tw: grpMap.values()) {

                    if (tw != null) {
                        outList.add(tw.trigger.getKey());
                    }
                }
            } else {
                outList = java.util.Collections.emptyList();
            }
        }

        return outList;
    }

    /**
     * <p>
     * Get the names of all of the <code>{@link org.quartz.Job}</code>
     * groups.
     * </p>
     */
    public List<String> getJobGroupNames() {
        List<String> outList = null;

        synchronized (lock) {
            outList = new LinkedList<String>(jobsByGroup.keySet());
        }

        return outList;
    }

    /**
     * <p>
     * Get the names of all of the <code>{@link org.quartz.Trigger}</code>
     * groups.
     * </p>
     */
    public List<String> getTriggerGroupNames() {
        LinkedList<String> outList = null;

        synchronized (lock) {
            outList = new LinkedList<String>(triggersByGroup.keySet());
        }

        return outList;
    }

    /**
     * <p>
     * Get all of the Triggers that are associated to the given Job.
     * </p>
     *
     * <p>
     * If there are no matches, a zero-length array should be returned.
     * </p>
     */
    public List<OperableTrigger> getTriggersForJob(JobKey jobKey) {
        ArrayList<OperableTrigger> trigList = new ArrayList<OperableTrigger>();

        synchronized (lock) {
            for (int i = 0; i < triggers.size(); i++) {
                TriggerWrapper tw = (TriggerWrapper) triggers.get(i);
                if (tw.jobKey.equals(jobKey)) {
                    trigList.add((OperableTrigger) tw.trigger.clone());
                }
            }
        }

        return trigList;
    }

    protected ArrayList<TriggerWrapper> getTriggerWrappersForJob(JobKey jobKey) {
        ArrayList<TriggerWrapper> trigList = new ArrayList<TriggerWrapper>();

        synchronized (lock) {
            for (int i = 0; i < triggers.size(); i++) {
                TriggerWrapper tw = (TriggerWrapper) triggers.get(i);
                if (tw.jobKey.equals(jobKey)) {
                    trigList.add(tw);
                }
            }
        }

        return trigList;
    }

    protected ArrayList<TriggerWrapper> getTriggerWrappersForCalendar(String calName) {
        ArrayList<TriggerWrapper> trigList = new ArrayList<TriggerWrapper>();

        synchronized (lock) {
            for (int i = 0; i < triggers.size(); i++) {
                TriggerWrapper tw = (TriggerWrapper) triggers.get(i);
                String tcalName = tw.getTrigger().getCalendarName();
                if (tcalName != null && tcalName.equals(calName)) {
                    trigList.add(tw);
                }
            }
        }

        return trigList;
    }

    /**
     * <p>
     * Pause the <code>{@link Trigger}</code> with the given name.
     * </p>
     *
     */
    public void pauseTrigger(TriggerKey triggerKey) {

        synchronized (lock) {
            TriggerWrapper tw = (TriggerWrapper) triggersByKey.get(triggerKey);
    
            // does the trigger exist?
            if (tw == null || tw.trigger == null) {
                return;
            }
    
            // if the trigger is "complete" pausing it does not make sense...
            if (tw.state == TriggerWrapper.STATE_COMPLETE) {
                return;
            }

            if(tw.state == TriggerWrapper.STATE_BLOCKED) {
                tw.state = TriggerWrapper.STATE_PAUSED_BLOCKED;
            } else {
                tw.state = TriggerWrapper.STATE_PAUSED;
            }

            timeTriggers.remove(tw);
        }
    }

    /**
     * <p>
     * Pause all of the <code>{@link Trigger}s</code> in the given group.
     * </p>
     *
     * <p>
     * The JobStore should "remember" that the group is paused, and impose the
     * pause on any new triggers that are added to the group while the group is
     * paused.
     * </p>
     *
     */
    public void pauseTriggerGroup(String groupName) {

        synchronized (lock) {
            if (pausedTriggerGroups.contains(groupName)) {
                return;
            }

            pausedTriggerGroups.add(groupName);
            List<TriggerKey> keys = getTriggerKeys(groupName);

            for (TriggerKey key: keys) {
                pauseTrigger(key);
            }
        }
    }

    /**
     * <p>
     * Pause the <code>{@link org.quartz.JobDetail}</code> with the given
     * name - by pausing all of its current <code>Trigger</code>s.
     * </p>
     *
     */
    public void pauseJob(JobKey jobKey) {
        synchronized (lock) {
            List<OperableTrigger> triggers = getTriggersForJob(jobKey);
            for (OperableTrigger trigger: triggers) {
                pauseTrigger(trigger.getKey());
            }
        }
    }

    /**
     * <p>
     * Pause all of the <code>{@link org.quartz.JobDetail}s</code> in the
     * given group - by pausing all of their <code>Trigger</code>s.
     * </p>
     *
     *
     * <p>
     * The JobStore should "remember" that the group is paused, and impose the
     * pause on any new jobs that are added to the group while the group is
     * paused.
     * </p>
     */
    public void pauseJobGroup(String groupName) {
        synchronized (lock) {
            if (!pausedJobGroups.contains(groupName)) {
        	    pausedJobGroups.add(groupName);
            }
            
            List<JobKey> jobNames = getJobKeys(groupName);

            for (JobKey jobKey: jobNames) {
                List<OperableTrigger> triggers = getTriggersForJob(jobKey);
                for (OperableTrigger trigger: triggers) {
                    pauseTrigger(trigger.getKey());
                }
            }
        }
    }

    /**
     * <p>
     * Resume (un-pause) the <code>{@link Trigger}</code> with the given
     * key.
     * </p>
     *
     * <p>
     * If the <code>Trigger</code> missed one or more fire-times, then the
     * <code>Trigger</code>'s misfire instruction will be applied.
     * </p>
     *
     */
    public void resumeTrigger(TriggerKey triggerKey) {

        synchronized (lock) {
            TriggerWrapper tw = (TriggerWrapper) triggersByKey.get(triggerKey);
    
            // does the trigger exist?
            if (tw == null || tw.trigger == null) {
                return;
            }
    
            OperableTrigger trig = tw.getTrigger();
    
            // if the trigger is not paused resuming it does not make sense...
            if (tw.state != TriggerWrapper.STATE_PAUSED &&
                    tw.state != TriggerWrapper.STATE_PAUSED_BLOCKED) {
                return;
            }

            if(blockedJobs.contains( trig.getJobKey() )) {
                tw.state = TriggerWrapper.STATE_BLOCKED;
            } else {
                tw.state = TriggerWrapper.STATE_WAITING;
            }

            applyMisfire(tw);

            if (tw.state == TriggerWrapper.STATE_WAITING) {
                timeTriggers.add(tw);
            }
        }
    }

    /**
     * <p>
     * Resume (un-pause) all of the <code>{@link Trigger}s</code> in the
     * given group.
     * </p>
     *
     * <p>
     * If any <code>Trigger</code> missed one or more fire-times, then the
     * <code>Trigger</code>'s misfire instruction will be applied.
     * </p>
     *
     */
    public void resumeTriggerGroup(String groupName) {

        synchronized (lock) {
            List<TriggerKey> keys = getTriggerKeys(groupName);

            for (TriggerKey triggerKey: keys) {
            	if(triggersByKey.get(triggerKey) != null) {
            		String jobGroup = ((TriggerWrapper) triggersByKey.get(triggerKey)).jobKey.getGroup();
            		if(pausedJobGroups.contains(jobGroup)) {
            			continue;
            		}
            	}
                resumeTrigger(triggerKey);
            }
            pausedTriggerGroups.remove(groupName);
        }
    }

    /**
     * <p>
     * Resume (un-pause) the <code>{@link org.quartz.JobDetail}</code> with
     * the given name.
     * </p>
     *
     * <p>
     * If any of the <code>Job</code>'s<code>Trigger</code> s missed one
     * or more fire-times, then the <code>Trigger</code>'s misfire
     * instruction will be applied.
     * </p>
     *
     */
    public void resumeJob(JobKey jobKey) {

        synchronized (lock) {
            List<OperableTrigger> triggers = getTriggersForJob(jobKey);
            for (OperableTrigger trigger: triggers) {
                resumeTrigger(trigger.getKey());
            }
        }
    }

    /**
     * <p>
     * Resume (un-pause) all of the <code>{@link org.quartz.JobDetail}s</code>
     * in the given group.
     * </p>
     *
     * <p>
     * If any of the <code>Job</code> s had <code>Trigger</code> s that
     * missed one or more fire-times, then the <code>Trigger</code>'s
     * misfire instruction will be applied.
     * </p>
     *
     */
    public void resumeJobGroup(String groupName) {
        synchronized (lock) {
            List<JobKey> keys = getJobKeys(groupName);

            if(pausedJobGroups.contains(groupName)) {
            	pausedJobGroups.remove(groupName);
            }
            
            for (JobKey key: keys) {
                List<OperableTrigger> triggers = getTriggersForJob(key);
                for (OperableTrigger trigger: triggers) {
                    resumeTrigger(trigger.getKey());
                }
            }
        }
    }

    /**
     * <p>
     * Pause all triggers - equivalent of calling <code>pauseTriggerGroup(group)</code>
     * on every group.
     * </p>
     *
     * <p>
     * When <code>resumeAll()</code> is called (to un-pause), trigger misfire
     * instructions WILL be applied.
     * </p>
     *
     * @see #resumeAll(SchedulingContext)
     * @see #pauseTriggerGroup(SchedulingContext, String)
     */
    public void pauseAll() {

        synchronized (lock) {
            List<String> names = getTriggerGroupNames();

            for (String name: names) {
                pauseTriggerGroup(name);
            }
        }
    }

    /**
     * <p>
     * Resume (un-pause) all triggers - equivalent of calling <code>resumeTriggerGroup(group)</code>
     * on every group.
     * </p>
     *
     * <p>
     * If any <code>Trigger</code> missed one or more fire-times, then the
     * <code>Trigger</code>'s misfire instruction will be applied.
     * </p>
     *
     * @see #pauseAll(SchedulingContext)
     */
    public void resumeAll() {

        synchronized (lock) {
        	pausedJobGroups.clear();
            List<String> names = getTriggerGroupNames();

            for (String name: names) {
                resumeTriggerGroup(name);
            }
        }
    }

    protected boolean applyMisfire(TriggerWrapper tw) {

        long misfireTime = System.currentTimeMillis();
        if (getMisfireThreshold() > 0) {
            misfireTime -= getMisfireThreshold();
        }

        Date tnft = tw.trigger.getNextFireTime();
        if (tnft == null || tnft.getTime() > misfireTime) { 
            return false; 
        }

        Calendar cal = null;
        if (tw.trigger.getCalendarName() != null) {
            cal = retrieveCalendar(tw.trigger.getCalendarName());
        }

        signaler.notifyTriggerListenersMisfired((OperableTrigger)tw.trigger.clone());

        tw.trigger.updateAfterMisfire(cal);

        if (tw.trigger.getNextFireTime() == null) {
            tw.state = TriggerWrapper.STATE_COMPLETE;
            signaler.notifySchedulerListenersFinalized(tw.trigger);
            synchronized (lock) {
                timeTriggers.remove(tw);
            }
        } else if (tnft.equals(tw.trigger.getNextFireTime())) {
            return false;
        }

        return true;
    }

    private static AtomicLong ftrCtr = new AtomicLong(System.currentTimeMillis());

    protected String getFiredTriggerRecordId() {
        return String.valueOf(ftrCtr.incrementAndGet());
    }

    /**
     * <p>
     * Get a handle to the next trigger to be fired, and mark it as 'reserved'
     * by the calling scheduler.
     * </p>
     *
     * @see #releaseAcquiredTrigger(SchedulingContext, Trigger)
     */
    public List<OperableTrigger> acquireNextTriggers(long noLaterThan, int maxCount, long timeWindow) {
        synchronized (lock) {
            List<OperableTrigger> result = new ArrayList<OperableTrigger>();

            while (true) {
                TriggerWrapper tw;

                try {
                    tw = (TriggerWrapper) timeTriggers.first();
                    if (tw == null) return result;
                    timeTriggers.remove(tw);
                } catch (java.util.NoSuchElementException nsee) {
                    return result;
                }

                if (tw.trigger.getNextFireTime() == null) {
                    continue;
                }

                if (applyMisfire(tw)) {
                    if (tw.trigger.getNextFireTime() != null) {
                        timeTriggers.add(tw);
                    }
                    continue;
                }

                if (tw.getTrigger().getNextFireTime().getTime() > noLaterThan + timeWindow) {
                    timeTriggers.add(tw);
                    return result;
                }

                tw.state = TriggerWrapper.STATE_ACQUIRED;

                tw.trigger.setFireInstanceId(getFiredTriggerRecordId());
                OperableTrigger trig = (OperableTrigger) tw.trigger.clone();
                result.add(trig);

                if (result.size() == maxCount)
                  return result;
            }
        }
    }

    /**
     * <p>
     * Inform the <code>JobStore</code> that the scheduler no longer plans to
     * fire the given <code>Trigger</code>, that it had previously acquired
     * (reserved).
     * </p>
     */
    public void releaseAcquiredTrigger(OperableTrigger trigger) {
        synchronized (lock) {
            TriggerWrapper tw = (TriggerWrapper) triggersByKey.get(trigger.getKey());
            if (tw != null && tw.state == TriggerWrapper.STATE_ACQUIRED) {
                tw.state = TriggerWrapper.STATE_WAITING;
                timeTriggers.add(tw);
            }
        }
    }

    /**
     * <p>
     * Inform the <code>JobStore</code> that the scheduler is now firing the
     * given <code>Trigger</code> (executing its associated <code>Job</code>),
     * that it had previously acquired (reserved).
     * </p>
     */
    public List<TriggerFiredResult> triggersFired(List<OperableTrigger> triggers) {

        synchronized (lock) {
            List<TriggerFiredResult> results = new ArrayList<TriggerFiredResult>();

            for (OperableTrigger trigger : triggers) {
                TriggerWrapper tw = (TriggerWrapper) triggersByKey.get(trigger.getKey());
                // was the trigger deleted since being acquired?
                if (tw == null || tw.trigger == null) {
                    return null;
                }
                // was the trigger completed, paused, blocked, etc. since being acquired?
                if (tw.state != TriggerWrapper.STATE_ACQUIRED) {
                    return null;
                }

                Calendar cal = null;
                if (tw.trigger.getCalendarName() != null) {
                    cal = retrieveCalendar(tw.trigger.getCalendarName());
                    if(cal == null)
                        return null;
                }
                Date prevFireTime = trigger.getPreviousFireTime();
                // in case trigger was replaced between acquiring and firing
                timeTriggers.remove(tw);
                // call triggered on our copy, and the scheduler's copy
                tw.trigger.triggered(cal);
                trigger.triggered(cal);
                //tw.state = TriggerWrapper.STATE_EXECUTING;
                tw.state = TriggerWrapper.STATE_WAITING;

                TriggerFiredBundle bndle = new TriggerFiredBundle(retrieveJob(
                        tw.jobKey), trigger, cal,
                        false, new Date(), trigger.getPreviousFireTime(), prevFireTime,
                        trigger.getNextFireTime());

                JobDetail job = bndle.getJobDetail();

                if (job.isConcurrentExectionDisallowed()) {
                    ArrayList trigs = getTriggerWrappersForJob(job.getKey());
                    Iterator itr = trigs.iterator();
                    while (itr.hasNext()) {
                        TriggerWrapper ttw = (TriggerWrapper) itr.next();
                        if(ttw.state == TriggerWrapper.STATE_WAITING) {
                            ttw.state = TriggerWrapper.STATE_BLOCKED;
                        }
                        if(ttw.state == TriggerWrapper.STATE_PAUSED) {
                            ttw.state = TriggerWrapper.STATE_PAUSED_BLOCKED;
                        }
                        timeTriggers.remove(ttw);
                    }
                    blockedJobs.add(job.getKey());
                } else if (tw.trigger.getNextFireTime() != null) {
                    synchronized (lock) {
                        timeTriggers.add(tw);
                    }
                }

                results.add(new TriggerFiredResult(bndle));
            }
            return results;
        }
    }

    /**
     * <p>
     * Inform the <code>JobStore</code> that the scheduler has completed the
     * firing of the given <code>Trigger</code> (and the execution its
     * associated <code>Job</code>), and that the <code>{@link org.quartz.JobDataMap}</code>
     * in the given <code>JobDetail</code> should be updated if the <code>Job</code>
     * is stateful.
     * </p>
     */
    public void triggeredJobComplete(OperableTrigger trigger,
            JobDetail jobDetail, int triggerInstCode) {

        synchronized (lock) {

            JobWrapper jw = (JobWrapper) jobsByKey.get(jobDetail.getKey());
            TriggerWrapper tw = (TriggerWrapper) triggersByKey.get(trigger.getKey());

            // It's possible that the job is null if:
            //   1- it was deleted during execution
            //   2- RAMJobStore is being used only for volatile jobs / triggers
            //      from the JDBC job store
            if (jw != null) {
                JobDetail jd = jw.jobDetail;

                if (jd.isPersistJobDataAfterExecution()) {
                    JobDataMap newData = jobDetail.getJobDataMap();
                    if (newData != null) {
                        newData = (JobDataMap)newData.clone();
                        newData.clearDirtyFlag();
                    }
                    ((JobDetailImpl)jd).setJobDataMap(newData);
                }
                if (jd.isConcurrentExectionDisallowed()) {
                    blockedJobs.remove(jd.getKey());
                    ArrayList<TriggerWrapper> trigs = getTriggerWrappersForJob(jd.getKey());
                    for(TriggerWrapper ttw : trigs) {
                        if (ttw.state == TriggerWrapper.STATE_BLOCKED) {
                            ttw.state = TriggerWrapper.STATE_WAITING;
                            timeTriggers.add(ttw);
                        }
                        if (ttw.state == TriggerWrapper.STATE_PAUSED_BLOCKED) {
                            ttw.state = TriggerWrapper.STATE_PAUSED;
                        }
                    }
                    signaler.signalSchedulingChange(0L);
                }
            } else { // even if it was deleted, there may be cleanup to do
                blockedJobs.remove(jobDetail.getKey());
            }
    
            // check for trigger deleted during execution...
            if (tw != null) {
                if (triggerInstCode == Trigger.INSTRUCTION_DELETE_TRIGGER) {
                    
                    if(trigger.getNextFireTime() == null) {
                        // double check for possible reschedule within job 
                        // execution, which would cancel the need to delete...
                        if(tw.getTrigger().getNextFireTime() == null) {
                            removeTrigger(trigger.getKey());
                        }
                    } else {
                        removeTrigger(trigger.getKey());
                        signaler.signalSchedulingChange(0L);
                    }
                } else if (triggerInstCode == Trigger.INSTRUCTION_SET_TRIGGER_COMPLETE) {
                    tw.state = TriggerWrapper.STATE_COMPLETE;
                    timeTriggers.remove(tw);
                    signaler.signalSchedulingChange(0L);
                } else if(triggerInstCode == Trigger.INSTRUCTION_SET_TRIGGER_ERROR) {
                    getLog().info("Trigger " + trigger.getKey() + " set to ERROR state.");
                    tw.state = TriggerWrapper.STATE_ERROR;
                    signaler.signalSchedulingChange(0L);
                } else if (triggerInstCode == Trigger.INSTRUCTION_SET_ALL_JOB_TRIGGERS_ERROR) {
                    getLog().info("All triggers of Job " 
                            + trigger.getJobKey() + " set to ERROR state.");
                    setAllTriggersOfJobToState(trigger.getJobKey(), TriggerWrapper.STATE_ERROR);
                    signaler.signalSchedulingChange(0L);
                } else if (triggerInstCode == Trigger.INSTRUCTION_SET_ALL_JOB_TRIGGERS_COMPLETE) {
                    setAllTriggersOfJobToState(trigger.getJobKey(), TriggerWrapper.STATE_COMPLETE);
                    signaler.signalSchedulingChange(0L);
                }
            }
        }
    }

    protected void setAllTriggersOfJobToState(JobKey jobKey, int state) {
        ArrayList tws = getTriggerWrappersForJob(jobKey);
        Iterator itr = tws.iterator();
        while (itr.hasNext()) {
            TriggerWrapper tw = (TriggerWrapper) itr.next();
            tw.state = state;
            if(state != TriggerWrapper.STATE_WAITING) {
                timeTriggers.remove(tw);
            }
        }
    }
    
    protected String peekTriggers() {

        StringBuffer str = new StringBuffer();
        TriggerWrapper tw = null;
        synchronized (lock) {
            for (Iterator valueIter = triggersByKey.values().iterator(); valueIter.hasNext();) {
                tw = (TriggerWrapper)valueIter.next();
                str.append(tw.trigger.getKey().getName());
                str.append("/");
            }
        }
        str.append(" | ");

        synchronized (lock) {
            Iterator itr = timeTriggers.iterator();
            while (itr.hasNext()) {
                tw = (TriggerWrapper) itr.next();
                str.append(tw.trigger.getKey().getName());
                str.append("->");
            }
        }

        return str.toString();
    }

    /** 
     * @see org.quartz.spi.JobStore#getPausedTriggerGroups(org.quartz.core.SchedulingContext)
     */
    public Set getPausedTriggerGroups() throws JobPersistenceException {
        HashSet set = new HashSet();
        
        set.addAll(pausedTriggerGroups);
        
        return set;
    }

    public void setInstanceId(String schedInstId) {
        //
    }

    public void setInstanceName(String schedName) {
        //
    }

    public long getEstimatedTimeToReleaseAndAcquireTrigger() {
        return 5;
    }

    public boolean isClustered() {
        return false;
    }

}

/*******************************************************************************
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * 
 * Helper Classes. * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 */

class TriggerComparator implements Comparator<TriggerWrapper> {

    public int compare(TriggerWrapper obj1, TriggerWrapper obj2) {
        TriggerWrapper trig1 = (TriggerWrapper) obj1;
        TriggerWrapper trig2 = (TriggerWrapper) obj2;

        int comp = trig1.trigger.compareTo(trig2.trigger);
        if (comp != 0) {
            return comp;
        }

        comp = trig2.trigger.getPriority() - trig1.trigger.getPriority();
        if (comp != 0) {
            return comp;
        }
        
        return trig1.trigger.getKey().compareTo(trig2.trigger.getKey());
    }

    public boolean equals(Object obj) {
        return (obj instanceof TriggerComparator);
    }
}

class JobWrapper {

    public JobKey key;

    public JobDetail jobDetail;

    JobWrapper(JobDetail jobDetail) {
        this.jobDetail = jobDetail;
        key = jobDetail.getKey();
    }

    public boolean equals(Object obj) {
        if (obj instanceof JobWrapper) {
            JobWrapper jw = (JobWrapper) obj;
            if (jw.key.equals(this.key)) {
                return true;
            }
        }

        return false;
    }
    
    public int hashCode() {
        return key.hashCode(); 
    }
    

}

class TriggerWrapper {

    public TriggerKey key;

    public JobKey jobKey;

    public OperableTrigger trigger;

    public int state = STATE_WAITING;

    public static final int STATE_WAITING = 0;

    public static final int STATE_ACQUIRED = 1;

    public static final int STATE_EXECUTING = 2;

    public static final int STATE_COMPLETE = 3;

    public static final int STATE_PAUSED = 4;

    public static final int STATE_BLOCKED = 5;

    public static final int STATE_PAUSED_BLOCKED = 6;

    public static final int STATE_ERROR = 7;
    
    TriggerWrapper(OperableTrigger trigger) {
        this.trigger = trigger;
        key = trigger.getKey();
        this.jobKey = trigger.getJobKey();
    }

    public boolean equals(Object obj) {
        if (obj instanceof TriggerWrapper) {
            TriggerWrapper tw = (TriggerWrapper) obj;
            if (tw.key.equals(this.key)) {
                return true;
            }
        }

        return false;
    }

    public int hashCode() {
        return key.hashCode(); 
    }

    
    public OperableTrigger getTrigger() {
        return this.trigger;
    }
}
