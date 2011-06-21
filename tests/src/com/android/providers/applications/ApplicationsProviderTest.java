/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.applications;

import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Applications;
import android.test.ProviderTestCase2;
import android.test.suitebuilder.annotation.LargeTest;

import java.util.concurrent.FutureTask;


/**
 * Instrumentation test for the ApplicationsProvider.
 *
 * The tests use an IsolatedContext, and are not affected by the real list of
 * applications on the device. The ApplicationsProvider's persistent database
 * is also created in an isolated context so it doesn't interfere with the
 * database of the actual ApplicationsProvider installed on the device.
 */
@LargeTest
public class ApplicationsProviderTest extends ProviderTestCase2<ApplicationsProviderForTesting> {

    private ApplicationsProviderForTesting mProvider;

    private MockActivityManager mMockActivityManager;

    public ApplicationsProviderTest() {
        super(ApplicationsProviderForTesting.class, Applications.AUTHORITY);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mProvider = getProvider();
        mMockActivityManager = new MockActivityManager();
        initProvider(mProvider);
    }

    /**
     * Ensures that the ApplicationsProvider is in a ready-to-test state.
     */
    private void initProvider(ApplicationsProviderForTesting provider) throws Exception {
        // Decouple the provider from Android's real list of applications.
        MockPackageManager mockPackageManager = new MockPackageManager();
        addDefaultTestPackages(mockPackageManager);
        provider.setMockPackageManager(mockPackageManager);
        provider.setMockActivityManager(mMockActivityManager);

        // We need to wait for the applications database to be updated (it's
        // updated with a slight delay by a separate thread) before we can use
        // the ApplicationsProvider.
        Runnable markerRunnable = new Runnable() {
            @Override
            public void run() {
            }
        };
        FutureTask<Void> onApplicationsListUpdated = new FutureTask<Void>(markerRunnable, null);

        provider.setOnApplicationsListUpdated(onApplicationsListUpdated);
        onApplicationsListUpdated.get();
    }

    /**
     * Register a few default applications with the ApplicationsProvider that
     * tests can query.
     */
    private void addDefaultTestPackages(MockPackageManager mockPackageManager) {
        mockPackageManager.addPackage(
                "Email", new ComponentName("com.android.email", "com.android.email.MainView"));
        mockPackageManager.addPackage(
                "Ebay", new ComponentName("com.android.ebay", "com.android.ebay.Shopping"));
        mockPackageManager.addPackage(
                "Fakeapp", new ComponentName("com.android.fakeapp", "com.android.fakeapp.FakeView"));

        // Apps that can be used to test ordering.
        mockPackageManager.addPackage("AlphabeticA", new ComponentName("a", "a.AView"));
        mockPackageManager.addPackage("AlphabeticB", new ComponentName("b", "b.BView"));
        mockPackageManager.addPackage("AlphabeticC", new ComponentName("c", "c.CView"));
        mockPackageManager.addPackage("AlphabeticD", new ComponentName("d", "d.DView"));
        mockPackageManager.addPackage("AlphabeticD2", new ComponentName("d", "d.DView2"));
    }

    public void testSearch_singleResult() {
        testSearch("ema", "Email");
    }

    public void testSearch_multipleResults() {
        testSearch("e", "Ebay", "Email");
    }

    public void testSearch_noResults() {
        testSearch("nosuchapp");
    }

    public void testSearch_orderingIsAlphabeticByDefault() {
        testSearch("alphabetic", "AlphabeticA", "AlphabeticB", "AlphabeticC", "AlphabeticD",
                "AlphabeticD2");
    }

    public void testSearch_emptySearchQueryReturnsEverything() {
        testSearch("",
                "AlphabeticA", "AlphabeticB", "AlphabeticC", "AlphabeticD", "AlphabeticD2",
                "Ebay", "Email", "Fakeapp");
    }

    public void testSearch_appsAreRankedByUsageTimeOnStartup() throws Exception {
        mMockActivityManager.addLastResumeTime("d", "d.DView", 3);
        mMockActivityManager.addLastResumeTime("b", "b.BView", 1);
        // Missing usage time for "a".
        mMockActivityManager.addLastResumeTime("c", "c.CView", 0);

        // Launch count database is populated on startup.
        mProvider = createNewProvider(getMockContext());
        mProvider.setHasGlobalSearchPermission(true);
        initProvider(mProvider);

        // Override the previous provider with the new instance in the
        // ContentResolver.
        getMockContentResolver().addProvider(Applications.AUTHORITY, mProvider);

        // New ranking: D, B, A, C (first by launch count, then
        // - if the launch counts of two apps are equal - alphabetically)
        testSearch("alphabetic", "AlphabeticD", "AlphabeticB", "AlphabeticA", "AlphabeticC",
                "AlphabeticD2");
    }

    public void testSearch_appsAreRankedByResumeTimeAfterUpdate() {
        mProvider.setHasGlobalSearchPermission(true);

        mMockActivityManager.addLastResumeTime("d", "d.DView", 3);
        mMockActivityManager.addLastResumeTime("b", "b.BView", 1);
        // Missing launch count for "a".
        mMockActivityManager.addLastResumeTime("c", "c.CView", 0);

        // Fetch new data from usage stat provider (in the real instance this
        // is triggered by a zero-query from global search).
        mProvider.updateUsageStats();

        // New ranking: D, B, A, C (first by launch count, then
        // - if the launch counts of two apps are equal - alphabetically)
        testSearch("alphabetic", "AlphabeticD", "AlphabeticB", "AlphabeticA", "AlphabeticC",
                "AlphabeticD2");
    }

    public void testSearch_noLastAccessTimesWithoutPermission() {
        mProvider.setHasGlobalSearchPermission(false);
        mMockActivityManager.addLastResumeTime("d", "d.DView", 1);
        mMockActivityManager.addLastResumeTime("b", "b.BView", 2);
        mMockActivityManager.addLastResumeTime("c", "c.CView", 3);

        assertNull(getGlobalSearchCursor("", false));

        Cursor cursor = getGlobalSearchCursor("alphabetic", false);
        assertEquals(-1, cursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_LAST_ACCESS_HINT));
    }

    public void testSearch_lastAccessTimes() {
        mProvider.setHasGlobalSearchPermission(true);
        mMockActivityManager.addLastResumeTime("d", "d.DView", 1);
        mMockActivityManager.addLastResumeTime("b", "b.BView", 2);
        mMockActivityManager.addLastResumeTime("c", "c.CView", 3);

        testLastAccessTimes("", true, 3, 2, 1, 0, 0, 0, 0, 0, 0, 0);

        testLastAccessTimes("alphabetic", true, 3, 2, 1, 0, 0);

        // Update the last resume time of "c".
        mMockActivityManager.addLastResumeTime("c", "c.CView", 5);
        // Without a refresh, we should see the same results as before.
        testLastAccessTimes("alphabetic", false, 3, 2, 1, 0, 0);
        // If we refresh, we should see the change.
        testLastAccessTimes("alphabetic", true, 5, 2, 1, 0, 0);
    }

    /**
     * The ApplicationsProvider must only rank by launch count if the caller
     * is a privileged application - ordering apps by launch count when asked
     * by a regular application would leak information about user behavior.
     */
    public void testSearch_notAllowedToRankByLaunchCount() {
        // Simulate non-privileged calling application.
        mProvider.setHasGlobalSearchPermission(false);

        mMockActivityManager.addLastResumeTime("d", "d.DView", 3);
        mMockActivityManager.addLastResumeTime("b", "b.BView", 1);
        mMockActivityManager.addLastResumeTime("a", "a.AView", 0);
        mMockActivityManager.addLastResumeTime("c", "c.CView", 0);

        // Fetch new data from launch count provider.
        mProvider.updateUsageStats();

        // Launch count information mustn't be leaked - ranking is still
        // alphabetic.
        testSearch("alphabetic", "AlphabeticA", "AlphabeticB", "AlphabeticC", "AlphabeticD",
                "AlphabeticD2");
    }

    private void testSearch(String searchQuery, String... expectedResultsInOrder) {
        Cursor cursor = Applications.search(getMockContentResolver(), searchQuery);

        assertNotNull(cursor);
        assertFalse(cursor.isClosed());

        verifySearchResults(cursor, expectedResultsInOrder);

        cursor.close();
    }

    private void verifySearchResults(Cursor cursor, String... expectedResultsInOrder) {
        int expectedResultCount = expectedResultsInOrder.length;
        assertEquals("Wrong number of app search results.",
                expectedResultCount, cursor.getCount());

        if (expectedResultCount > 0) {
            cursor.moveToFirst();
            int nameIndex = cursor.getColumnIndex(ApplicationsProvider.NAME);
            // Verify that the actual results match the expected ones.
            for (int i = 0; i < cursor.getCount(); i++) {
                assertEquals("Wrong search result at position " + i,
                        expectedResultsInOrder[i], cursor.getString(nameIndex));
                cursor.moveToNext();
            }
        }
    }

    private Cursor getGlobalSearchCursor(String searchQuery, boolean refresh) {
        Uri.Builder uriBuilder = Applications.CONTENT_URI.buildUpon();
        uriBuilder.appendPath(SearchManager.SUGGEST_URI_PATH_QUERY).appendPath(searchQuery);
        if (refresh) {
            uriBuilder.appendQueryParameter(ApplicationsProvider.REFRESH_STATS, "");
        }
        return getMockContentResolver().query(uriBuilder.build(), null, null, null, null);
    }

    private void testLastAccessTimes(String searchQuery, boolean refresh,
            int... expectedLastAccessTimesInOrder) {
        Cursor cursor = getGlobalSearchCursor(searchQuery, refresh);

        assertNotNull(cursor);
        assertFalse(cursor.isClosed());

        verifyLastAccessTimes(cursor, expectedLastAccessTimesInOrder);

        cursor.close();
    }

    private void verifyLastAccessTimes(Cursor cursor, int... expectedLastAccessTimesInOrder) {
        cursor.moveToFirst();
        int lastAccessTimeIndex = cursor.getColumnIndex(
                SearchManager.SUGGEST_COLUMN_LAST_ACCESS_HINT);
        // Verify that the actual results match the expected ones.
        for (int i = 0; i < cursor.getCount(); i++) {
            assertEquals("Wrong last-access time at position " + i,
                    expectedLastAccessTimesInOrder[i], cursor.getInt(lastAccessTimeIndex));
            cursor.moveToNext();
        }
    }

    private ApplicationsProviderForTesting createNewProvider(Context context) throws Exception {
        ApplicationsProviderForTesting newProviderInstance =
                ApplicationsProviderForTesting.class.newInstance();
        newProviderInstance.attachInfo(context, null);
        return newProviderInstance;
    }
}
