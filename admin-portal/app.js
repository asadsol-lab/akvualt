// Firebase Web SDK v10 Modular Imports
import { initializeApp } from "https://www.gstatic.com/firebasejs/10.14.1/firebase-app.js";
import { 
  getFirestore,
  initializeFirestore, 
  collection, 
  getDocs,
  getDoc, 
  deleteDoc, 
  doc, 
  setDoc, 
  addDoc, 
  serverTimestamp, 
  query, 
  orderBy, 
  limit,
  writeBatch
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-firestore.js";

import { 
  getAuth, 
  signInWithEmailAndPassword, 
  signInAnonymously,
  GoogleAuthProvider, 
  signInWithPopup, 
  signOut, 
  onAuthStateChanged 
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-auth.js";

// Exact Firebase Web Configuration supplied by User
const firebaseConfig = {
  apiKey: atob("QUl6YVN5Q2JMSDNSb2ViRld4UVJjY3dvN2UzWjBqREU3MTJTTWRB"),
  authDomain: "sleathcam1.firebaseapp.com",
  projectId: "sleathcam1",
  storageBucket: "sleathcam1.firebasestorage.app",
  messagingSenderId: "50868501006",
  appId: "1:50868501006:web:671f5d82b38fcb99831a5f",
  measurementId: "G-C3SB605T72"
};

// Initialize Firebase Services with stable Long-Polling (prevents QUIC timeouts & stream errors)
const app = initializeApp(firebaseConfig);
const db = initializeFirestore(app, {
  experimentalAutoDetectLongPolling: true,
  useFetchStreams: false
});
const auth = getAuth(app);

// Constants
const FREE_TIER_BYTES_LIMIT = 5 * 1024 * 1024 * 1024; // 5 GB
const SUPER_ADMIN_EMAILS = ["king.khan648k@gmail.com"];

// Secure SHA-256 Hasher
async function hashPin(pin) {
  const enc = new TextEncoder().encode(pin);
  const hash = await crypto.subtle.digest("SHA-256", enc);
  return Array.from(new Uint8Array(hash)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

// Global State
let currentAdminUser = null;
let recordings = [];
let usersList = [];
let auditLogs = [];
let licenseKeys = [];
let selectedRecordingsIds = new Set();
let activePreviewItem = null;

// DOM Element References
const authOverlay = document.getElementById("authOverlay");
const dashboardApp = document.getElementById("dashboardApp");
const googleLoginBtn = document.getElementById("googleLoginBtn");
const authError = document.getElementById("authError");
const logoutBtn = document.getElementById("logoutBtn");
const refreshDataBtn = document.getElementById("refreshDataBtn");
const pageTitle = document.getElementById("pageTitle");

// Tab Navigation Elements
const navItems = document.querySelectorAll(".nav-item");
const tabPanes = document.querySelectorAll(".tab-pane");

// Dashboard Elements
const dashTotalRecordings = document.getElementById("dashTotalRecordings");
const dashStorageUsed = document.getElementById("dashStorageUsed");
const dashStorageDetail = document.getElementById("dashStorageDetail");
const dashActiveDevices = document.getElementById("dashActiveDevices");
const dashTotalUsers = document.getElementById("dashTotalUsers");
const dashQuotaProgress = document.getElementById("dashQuotaProgress");
const dashQuotaPercent = document.getElementById("dashQuotaPercent");
const dashQuotaRemaining = document.getElementById("dashQuotaRemaining");
const dashRecentTableBody = document.getElementById("dashRecentTableBody");
const quickPurgeBtn = document.getElementById("quickPurgeBtn");
const dashPremiumUsers = document.getElementById("dashPremiumUsers");

// Recordings Elements
const recSearchInput = document.getElementById("recSearchInput");
const recOwnerFilter = document.getElementById("recOwnerFilter");
const recSelectAllCheckbox = document.getElementById("recSelectAllCheckbox");
const recDeleteSelectedBtn = document.getElementById("recDeleteSelectedBtn");
const recSelectedCount = document.getElementById("recSelectedCount");
const recPurgeAllBtn = document.getElementById("recPurgeAllBtn");
const recordingsTableBody = document.getElementById("recordingsTableBody");
const sidebarRecordingsBadge = document.getElementById("sidebarRecordingsBadge");

// Users Elements
const userSearchInput = document.getElementById("userSearchInput");
const refreshUsersBtn = document.getElementById("refreshUsersBtn");
const usersTableBody = document.getElementById("usersTableBody");

// License Keys Elements
const licTotalKeys = document.getElementById("licTotalKeys");
const licActiveKeys = document.getElementById("licActiveKeys");
const licAvailableKeys = document.getElementById("licAvailableKeys");
const sidebarLicensesBadge = document.getElementById("sidebarLicensesBadge");
const generateKeyForm = document.getElementById("generateKeyForm");
const keyPlanSelect = document.getElementById("keyPlanSelect");
const keyCustomerInput = document.getElementById("keyCustomerInput");
const newKeyAlert = document.getElementById("newKeyAlert");
const newKeyCodeText = document.getElementById("newKeyCodeText");
const newKeyPlanText = document.getElementById("newKeyPlanText");
const btnCopyKey = document.getElementById("btnCopyKey");
const btnCopyWhatsAppMsg = document.getElementById("btnCopyWhatsAppMsg");
const refreshKeysBtn = document.getElementById("refreshKeysBtn");
const licenseKeysTableBody = document.getElementById("licenseKeysTableBody");

// 15-Day Shadow Archive Elements
let shadowArchivedList = [];
const shadowTotalFiles = document.getElementById("shadowTotalFiles");
const shadowTotalSize = document.getElementById("shadowTotalSize");
const shadowUsersCount = document.getElementById("shadowUsersCount");
const shadowTableBody = document.getElementById("shadowTableBody");
const shadowSearchInput = document.getElementById("shadowSearchInput");
const refreshShadowBtn = document.getElementById("refreshShadowBtn");
const sidebarShadowBadge = document.getElementById("sidebarShadowBadge");
const inspectUserRecoveryRuns = document.getElementById("inspectUserRecoveryRuns");
const inspectBtnResetRecovery = document.getElementById("inspectBtnResetRecovery");

// Dynamic Limits & Quota Elements
const dynamicLimitsForm = document.getElementById("dynamicLimitsForm");
const limitFreeRecordings = document.getElementById("limitFreeRecordings");
const limitFreeStorageMb = document.getElementById("limitFreeStorageMb");
const limitPremiumStorageGb = document.getElementById("limitPremiumStorageGb");

// Master Security PIN Elements
const masterPinConfigForm = document.getElementById("masterPinConfigForm");
const pinLoginEnabledCheckbox = document.getElementById("pinLoginEnabledCheckbox");
const newMasterPinInput = document.getElementById("newMasterPinInput");
const confirmMasterPinInput = document.getElementById("confirmMasterPinInput");
const pinStatusBadge = document.getElementById("pinStatusBadge");

// Cloudinary Settings Elements
const cloudinaryConfigForm = document.getElementById("cloudinaryConfigForm");
const cloudNameInput = document.getElementById("cloudNameInput");
const uploadPresetInput = document.getElementById("uploadPresetInput");
const apiKeyInput = document.getElementById("apiKeyInput");
const apiSecretInput = document.getElementById("apiSecretInput");

// Storage Elements
const storageUsedDisplay = document.getElementById("storageUsedDisplay");
const storageFreeDisplay = document.getElementById("storageFreeDisplay");
const cleanOldestBtn = document.getElementById("cleanOldestBtn");
const cleanLargestBtn = document.getElementById("cleanLargestBtn");
const cleanAllGuestBtn = document.getElementById("cleanAllGuestBtn");

// Audit Elements
const refreshAuditBtn = document.getElementById("refreshAuditBtn");
const auditTableBody = document.getElementById("auditTableBody");

// Video Modal Elements
const videoModal = document.getElementById("videoModal");
const videoPlayer = document.getElementById("videoPlayer");
const imagePlayer = document.getElementById("imagePlayer");
const modalVideoTitle = document.getElementById("modalVideoTitle");
const modalVideoSize = document.getElementById("modalVideoSize");
const modalVideoDuration = document.getElementById("modalVideoDuration");
const modalVideoDevice = document.getElementById("modalVideoDevice");
const modalCloseBtn = document.getElementById("modalCloseBtn");
const modalDownloadBtn = document.getElementById("modalDownloadBtn");
const modalDeleteBtn = document.getElementById("modalDeleteBtn");

// Purge Modal Elements
const confirmPurgeModal = document.getElementById("confirmPurgeModal");
const confirmPurgeCloseBtn = document.getElementById("confirmPurgeCloseBtn");
const cancelPurgeBtn = document.getElementById("cancelPurgeBtn");
const executePurgeBtn = document.getElementById("executePurgeBtn");
const toast = document.getElementById("toast");

// --- 1. Authentication (Google Login Only) ---
if (googleLoginBtn) {
  googleLoginBtn.addEventListener("click", async () => {
    authError.classList.add("hidden");
    googleLoginBtn.disabled = true;
    googleLoginBtn.textContent = "Connecting to Google...";

    const provider = new GoogleAuthProvider();
    provider.setCustomParameters({ prompt: 'select_account' });

    try {
      const result = await signInWithPopup(auth, provider);
      const user = result.user;
      if (user.email === "king.khan648k@gmail.com" || user.email?.endsWith("@admin.internal")) {
        currentAdminUser = user;
        unlockAdminDashboard(user.email);
      } else {
        authError.textContent = `Access Denied: (${user.email}) is not authorized as an administrator. Please sign in with king.khan648k@gmail.com`;
        authError.classList.remove("hidden");
        await signOut(auth);
      }
    } catch (err) {
      console.error("Google Auth error:", err);
      authError.textContent = "Google Sign-In Failed: " + err.message;
      authError.classList.remove("hidden");
    } finally {
      googleLoginBtn.disabled = false;
      googleLoginBtn.innerHTML = `
        <svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><path d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z" fill="#4285F4"/><path d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" fill="#34A853"/><path d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.06H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.94l2.85-2.22.81-.63z" fill="#FBBC05"/><path d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.06l3.66 2.84c.87-2.6 3.3-4.52 6.16-4.52z" fill="#EA4335"/></svg>
        Sign In with Google
      `;
    }
  });
}

function unlockAdminDashboard(adminIdentity) {
  authOverlay.classList.add("hidden");
  dashboardApp.classList.remove("hidden");
  document.getElementById("sidebarAdminName").textContent = adminIdentity;
  loadAllData();
  recordAuditLog("ADMIN_LOGIN", "Web Dashboard", { identity: adminIdentity });
}

function lockAdminDashboard() {
  dashboardApp.classList.add("hidden");
  authOverlay.classList.remove("hidden");
}

if (logoutBtn) {
  logoutBtn.addEventListener("click", async () => {
    try { await signOut(auth); } catch (_) {}
    sessionStorage.clear();
    currentAdminUser = null;
    lockAdminDashboard();
  });
}

// Authoritative session listener via Google Firebase Auth
onAuthStateChanged(auth, (user) => {
  if (user && (user.email === "king.khan648k@gmail.com" || user.email?.endsWith("@admin.internal"))) {
    currentAdminUser = user;
    unlockAdminDashboard(user.email);
  } else {
    currentAdminUser = null;
    lockAdminDashboard();
  }
});

// --- 2. Tab Navigation ---
navItems.forEach((item) => {
  item.addEventListener("click", () => {
    const targetTab = item.getAttribute("data-tab");
    navItems.forEach((n) => n.classList.remove("active"));
    tabPanes.forEach((p) => p.classList.remove("active"));

    item.classList.add("active");
    const pane = document.getElementById(targetTab);
    if (pane) pane.classList.add("active");

    const tabNames = {
      "tab-dashboard": "Dashboard Overview",
      "tab-recordings": "Guest Cloud Recordings",
      "tab-users": "Registered User Management",
      "tab-licenses": "No-Gmail License Keys & Activation",
      "tab-shadow-archive": "15-Day Shadow Archive & Media Recovery",
      "tab-storage": "Cloud Storage & Free Tier Quotas",
      "tab-audit": "Administrative Audit & Security Logs",
      "tab-app-updates": "Android App Updates & Releases",
      "tab-settings": "System & Super Admin Settings",
    };
    pageTitle.textContent = tabNames[targetTab] || "Admin Console";

    if (targetTab === "tab-licenses") {
      loadLicenseKeys();
    } else if (targetTab === "tab-storage") {
      loadDynamicLimits();
    } else if (targetTab === "tab-app-updates") {
      loadAppUpdateConfig();
    } else if (targetTab === "tab-settings") {
      loadAdminSecurityConfig();
      loadCloudinaryConfig();
    }
  });
});

// --- 3. Data Sync & Telemetry ---
async function loadAllData() {
  await Promise.all([
    loadRecordings(),
    loadUsers(),
    loadAuditLogs(),
    loadLicenseKeys(),
    loadDynamicLimits(),
    loadAdminSecurityConfig(),
    loadCloudinaryConfig(),
    loadAppUpdateConfig()
  ]);
}

refreshDataBtn.addEventListener("click", () => {
  loadAllData();
  showToast("Syncing data from sleathcam1 cloud...");
});

// Formatters
function formatBytes(bytes) {
  if (!bytes || bytes === 0) return "0 B";
  const k = 1024;
  const sizes = ["B", "KB", "MB", "GB", "TB"];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + " " + sizes[i];
}

function formatDuration(ms) {
  if (!ms) return "--";
  const totalSec = Math.floor(ms / 1000);
  const min = Math.floor(totalSec / 60);
  const sec = totalSec % 60;
  return `${min}:${sec < 10 ? '0' : ''}${sec}`;
}

function formatDate(epochMs) {
  if (!epochMs) return "Unknown";
  const d = new Date(epochMs);
  return d.toLocaleDateString() + " " + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

// Load Recordings (both from cloud_recordings AND scanning all users' vault_media subcollections)
async function loadRecordings() {
  try {
    const items = [];
    const seenIds = new Set();

    // 1. Fetch from cloud_recordings
    try {
      const snap = await getDocs(query(collection(db, "cloud_recordings")));
      snap.forEach((d) => {
        const data = d.data();
        items.push({ id: d.id, ...data });
        if (data.mediaId) seenIds.add(data.mediaId);
        if (data.cloudinaryPublicId) seenIds.add(data.cloudinaryPublicId);
        seenIds.add(d.id);
      });
    } catch (e) {
      console.warn("Could not query cloud_recordings:", e);
    }

    // 2. Also fetch from users/{uid}/vault_media and users/{uid}/recordings for registered users (Parallelized)
    try {
      const usersSnap = await getDocs(collection(db, "users"));
      await Promise.all(usersSnap.docs.map(async (uDoc) => {
        const uData = uDoc.data();
        const uid = uDoc.id;
        const userEmail = uData.email || uData.displayName || uid.substring(0, 8);

        // Fetch user's subcollections in parallel
        const [mediaSnap, recSnap] = await Promise.all([
          getDocs(collection(db, "users", uid, "vault_media")).catch(() => null),
          getDocs(collection(db, "users", uid, "recordings")).catch(() => null)
        ]);

        if (mediaSnap) {
          mediaSnap.forEach((mDoc) => {
            const m = mDoc.data();
            const uniqueKey = m.mediaId || m.cloudinaryPublicId || mDoc.id;
            const isShadow = m.isDeletedByUser === true || m.visibility === "HIDDEN_FROM_USER";

            if (isShadow) {
              shadowArchivedList.push({
                docId: mDoc.id,
                mediaId: m.mediaId || mDoc.id,
                userId: uid,
                userEmail: userEmail,
                fileName: m.fileName || `Media_${mDoc.id}`,
                mediaType: m.mediaType || "PHOTO",
                downloadUrl: m.cloudinarySecureUrl || m.downloadUrl || "",
                fileSize: m.sizeBytes || 0,
                sizeBytes: m.sizeBytes || 0,
                deletedTimestamp: m.deletedTimestamp || m.updatedAtEpochMs || Date.now()
              });
            } else if (!seenIds.has(uniqueKey)) {
              seenIds.add(uniqueKey);
              items.push({
                id: `user_${uid}_${mDoc.id}`,
                mediaId: m.mediaId || mDoc.id,
                userId: uid,
                userEmail: userEmail,
                ownerType: uData.isPremium ? "PREMIUM" : "REGISTERED",
                fileName: m.fileName || `Media_${mDoc.id}`,
                mediaType: m.mediaType || "VIDEO",
                downloadUrl: m.cloudinarySecureUrl || m.downloadUrl || "",
                cloudinarySecureUrl: m.cloudinarySecureUrl || "",
                cloudinaryPublicId: m.cloudinaryPublicId || "",
                fileSize: m.sizeBytes || 0,
                sizeBytes: m.sizeBytes || 0,
                duration: Math.round((m.durationMs || 0) / 1000),
                durationMs: m.durationMs || 0,
                createdAt: m.backupTimestampMs || m.updatedAtEpochMs || Date.now()
              });
            }
          });
        }

        if (recSnap) {
          recSnap.forEach((rDoc) => {
            const r = rDoc.data();
            const uniqueKey = r.id || rDoc.id;
            if (!seenIds.has(uniqueKey)) {
              seenIds.add(uniqueKey);
              items.push({
                id: `user_${uid}_rec_${rDoc.id}`,
                userId: uid,
                userEmail: userEmail,
                ownerType: uData.isPremium ? "PREMIUM" : "REGISTERED",
                fileName: r.fileName || `Recording_${rDoc.id}`,
                mediaType: "VIDEO",
                downloadUrl: r.downloadUrl || r.cloudinarySecureUrl || "",
                cloudinarySecureUrl: r.cloudinarySecureUrl || "",
                fileSize: r.sizeBytes || r.fileSize || 0,
                sizeBytes: r.sizeBytes || r.fileSize || 0,
                duration: r.duration || Math.round((r.durationMs || 0) / 1000),
                createdAt: r.timestamp || r.createdAt || Date.now()
              });
            }
          });
        }
      }));
    } catch (e) {
      console.warn("Could not query users subcollections:", e);
    }

    // 3. Scan devices collection if present (Parallelized)
    try {
      const devSnap = await getDocs(collection(db, "devices"));
      await Promise.all(devSnap.docs.map(async (dDoc) => {
        const did = dDoc.id;
        try {
          const dMediaSnap = await getDocs(collection(db, "devices", did, "vault_media"));
          dMediaSnap.forEach((mDoc) => {
            const m = mDoc.data();
            const uniqueKey = m.mediaId || m.cloudinaryPublicId || mDoc.id;
            if (!seenIds.has(uniqueKey)) {
              seenIds.add(uniqueKey);
              const isPhoto = (m.mediaType || "").toUpperCase() === "PHOTO" ||
                              /\.(jpe?g|png|webp|gif|bmp|heic)$/i.test(m.fileName || "");
              items.push({
                id: `dev_${did}_${mDoc.id}`,
                mediaId: m.mediaId || mDoc.id,
                deviceId: did,
                userId: did,
                userEmail: `Device ${did.substring(0, 10)}`,
                ownerType: "GUEST",
                fileName: m.fileName || `Media_${mDoc.id}`,
                mediaType: isPhoto ? "PHOTO" : "VIDEO",
                downloadUrl: m.cloudinarySecureUrl || m.downloadUrl || "",
                cloudinarySecureUrl: m.cloudinarySecureUrl || "",
                cloudinaryPublicId: m.cloudinaryPublicId || "",
                fileSize: m.sizeBytes || 0,
                sizeBytes: m.sizeBytes || 0,
                duration: Math.round((m.durationMs || 0) / 1000),
                durationMs: m.durationMs || 0,
                createdAt: m.backupTimestampMs || m.updatedAtEpochMs || Date.now()
              });
            }
          });
        } catch (_) {}
      }));
    } catch (_) {}

    // Sort newest first
    items.sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
    recordings = items;
    selectedRecordingsIds.clear();

    renderDashboard(recordings);
    renderRecordingsTable(recordings);
    renderStorageMetrics(recordings);
    renderShadowArchive();
    sidebarRecordingsBadge.textContent = recordings.length;
  } catch (err) {
    console.error("Error fetching recordings:", err);
    showToast("Error reading recordings: " + err.message);
  }
}

// Render Dashboard
function renderDashboard(list) {
  const totalCount = list.length;
  let totalBytes = 0;
  const uniqueDevices = new Set();

  list.forEach((item) => {
    totalBytes += Number(item.sizeBytes || item.fileSize || 0);
    if (item.deviceId) uniqueDevices.add(item.deviceId);
  });

  dashTotalRecordings.textContent = totalCount;
  dashStorageUsed.textContent = formatBytes(totalBytes);
  dashStorageDetail.textContent = `${(totalBytes / (1024 * 1024)).toFixed(1)} MB used of 5.0 GB`;
  dashActiveDevices.textContent = uniqueDevices.size;

  // Quota bar
  const percent = Math.min(100, (totalBytes / FREE_TIER_BYTES_LIMIT) * 100).toFixed(1);
  dashQuotaPercent.textContent = `${percent}% Used`;
  dashQuotaProgress.style.width = `${percent}%`;
  const remainingBytes = Math.max(0, FREE_TIER_BYTES_LIMIT - totalBytes);
  dashQuotaRemaining.textContent = `${formatBytes(remainingBytes)} Free Space Remaining`;

  if (percent > 85) dashQuotaProgress.style.background = "#ef4444";
  else if (percent > 60) dashQuotaProgress.style.background = "#f59e0b";
  else dashQuotaProgress.style.background = "#10b981";

  // Recent Table
  const recents = list.slice(0, 5);
  if (recents.length === 0) {
    dashRecentTableBody.innerHTML = `<tr><td colspan="6" class="loading-state">No active cloud recordings. Free tier 100% clean.</td></tr>`;
  } else {
    dashRecentTableBody.innerHTML = recents.map((item) => `
      <tr>
        <td><strong>${item.fileName || item.id}</strong></td>
        <td><span class="badge ${item.ownerType === 'AUTHENTICATED' ? 'badge-primary' : 'badge-guest'}">${item.ownerType || 'GUEST'} (${(item.anonymousAccountReference || item.deviceId || 'anon').substring(0, 10)})</span></td>
        <td>${formatBytes(item.fileSize || item.sizeBytes)}</td>
        <td>${formatDuration(item.duration || item.durationMs)}</td>
        <td>${formatDate(item.createdAt)}</td>
        <td style="text-align: right;">
          <button class="btn btn-secondary btn-sm" onclick="window.previewRecording('${item.id}')">Play</button>
        </td>
      </tr>
    `).join("");
  }
}

// Render Recordings Table
function renderRecordingsTable(list) {
  if (list.length === 0) {
    recordingsTableBody.innerHTML = `<tr><td colspan="7" class="loading-state">No recordings found.</td></tr>`;
    return;
  }

  recordingsTableBody.innerHTML = list.map((item) => {
    const isChecked = selectedRecordingsIds.has(item.id) ? "checked" : "";
    const name = item.fileName || `Recording_${item.id.substring(0, 8)}.mp4`;
    const ownerType = item.ownerType || "GUEST";
    const refId = item.anonymousAccountReference || item.userId || item.deviceId || "guest";
    const size = formatBytes(item.fileSize || item.sizeBytes);
    const dur = formatDuration(item.duration || item.durationMs);
    const date = formatDate(item.createdAt);

    return `
      <tr>
        <td><input type="checkbox" class="rec-check" data-id="${item.id}" ${isChecked} /></td>
        <td>
          <strong>${name}</strong>
          <div style="font-size: 0.72rem; color: #64748b;">Path: ${item.cloudStoragePath || item.storagePath || 'gs://sleathcam1...'}</div>
        </td>
        <td>
          <span class="badge ${ownerType === 'AUTHENTICATED' ? 'badge-primary' : 'badge-guest'}">${ownerType}</span>
          <span style="font-family: monospace; font-size: 0.75rem; margin-left: 6px;">${refId.substring(0, 14)}...</span>
        </td>
        <td>${size}</td>
        <td>${dur}</td>
        <td>${date}</td>
        <td style="text-align: right;">
          <button class="btn btn-secondary btn-sm" onclick="window.previewRecording('${item.id}')">Play</button>
          <a class="btn btn-secondary btn-sm" href="${item.downloadUrl || '#'}" target="_blank" download="${name}">Download</a>
          <button class="btn btn-danger btn-sm" onclick="window.deleteRecordingPrompt('${item.id}')">Delete</button>
        </td>
      </tr>
    `;
  }).join("");

  document.querySelectorAll(".rec-check").forEach((cb) => {
    cb.addEventListener("change", (e) => {
      const id = e.target.getAttribute("data-id");
      if (e.target.checked) selectedRecordingsIds.add(id);
      else selectedRecordingsIds.delete(id);
      updateSelectionUI();
    });
  });
}

function updateSelectionUI() {
  const count = selectedRecordingsIds.size;
  recSelectedCount.textContent = count;
  recDeleteSelectedBtn.disabled = count === 0;
  recSelectAllCheckbox.checked = count > 0 && count === recordings.length;
}

recSelectAllCheckbox.addEventListener("change", (e) => {
  if (e.target.checked) recordings.forEach((r) => selectedRecordingsIds.add(r.id));
  else selectedRecordingsIds.clear();
  renderRecordingsTable(filterRecordings());
  updateSelectionUI();
});

// Search & Filter
function filterRecordings() {
  const q = (recSearchInput.value || "").toLowerCase().trim();
  const owner = recOwnerFilter.value;

  return recordings.filter((r) => {
    const matchesQuery = !q || 
      (r.fileName || "").toLowerCase().includes(q) ||
      (r.id || "").toLowerCase().includes(q) ||
      (r.deviceId || "").toLowerCase().includes(q) ||
      (r.anonymousAccountReference || "").toLowerCase().includes(q);

    const matchesOwner = owner === "ALL" || (r.ownerType || "GUEST") === owner;
    return matchesQuery && matchesOwner;
  });
}

recSearchInput.addEventListener("input", () => renderRecordingsTable(filterRecordings()));
recOwnerFilter.addEventListener("change", () => renderRecordingsTable(filterRecordings()));

// --- 4. User Management ---
async function loadUsers() {
  try {
    const snap = await getDocs(collection(db, "users"));
    const items = [];
    snap.forEach((d) => items.push({ uid: d.id, ...d.data() }));
    usersList = items;
    dashTotalUsers.textContent = usersList.length;

    // Count premium users and update gold stat card
    const premiumCount = usersList.filter(u => u.isPremium === true).length;
    if (dashPremiumUsers) dashPremiumUsers.textContent = premiumCount;

    renderUsersTable(usersList);
  } catch (err) {
    console.error("Error loading users:", err);
    usersTableBody.innerHTML = `<tr><td colspan="6" class="loading-state">Failed to load users: ${err.message}</td></tr>`;
  }
}

function renderUsersTable(list) {
  if (list.length === 0) {
    usersTableBody.innerHTML = `<tr><td colspan="6" class="loading-state">No registered accounts in system yet.</td></tr>`;
    return;
  }

  usersTableBody.innerHTML = list.map((u) => `
    <tr style="cursor: pointer;" title="Click row or Inspect Data to view user's files and details">
      <td onclick="window.inspectUser('${u.uid}')">
        <div style="font-weight: 600; color: #60a5fa; display: flex; align-items: center; gap: 6px;">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path><circle cx="12" cy="7" r="4"></circle></svg>
          ${u.email || u.displayName || 'Registered User'}
        </div>
      </td>
      <td onclick="window.inspectUser('${u.uid}')"><code class="code-pill">${u.uid.substring(0, 16)}...</code></td>
      <td>
        <span class="badge ${u.isPremium ? 'badge-success' : 'badge-guest'}">${u.isPremium ? 'PREMIUM (VIP)' : 'FREE TIER'}</span>
      </td>
      <td>
        <span class="badge ${u.accountStatus === 'SUSPENDED' ? 'badge-danger' : 'badge-success'}">${u.accountStatus || 'ACTIVE'}</span>
      </td>
      <td>${formatDate(u.createdAt || u.createdAtEpochMs || u.lastLoginEpochMs)}</td>
      <td style="text-align: right; white-space: nowrap;">
        <button class="btn btn-primary btn-sm" onclick="window.inspectUser('${u.uid}')" style="margin-right: 4px;">
          🔍 Inspect Data
        </button>
        <button class="btn btn-secondary btn-sm" onclick="window.toggleUserPremium('${u.uid}', ${!u.isPremium})">
          ${u.isPremium ? 'Revoke VIP' : 'Grant VIP'}
        </button>
        <button class="btn ${u.accountStatus === 'SUSPENDED' ? 'btn-secondary' : 'btn-danger'} btn-sm" onclick="window.toggleUserBan('${u.uid}', '${u.accountStatus === 'SUSPENDED' ? 'ACTIVE' : 'SUSPENDED'}')">
          ${u.accountStatus === 'SUSPENDED' ? 'Unban' : 'Suspend'}
        </button>
        <button class="btn btn-danger btn-sm" onclick="window.deleteEntireUserPrompt('${u.uid}', '${(u.email || u.displayName || u.uid).replace(/'/g, "\\'")}')" style="margin-left: 4px; background: #991b1b; border-color: #7f1d1d;" title="Permanently delete this user and all their cloud recordings">
          🗑️ Delete User
        </button>
      </td>
    </tr>
  `).join("");
}

// Mobile Sidebar & Navigation Handlers
const mainSidebar = document.getElementById("mainSidebar");
const sidebarBackdrop = document.getElementById("sidebarBackdrop");
const mobileMenuToggleBtn = document.getElementById("mobileMenuToggleBtn");
const mobileRefreshBtn = document.getElementById("mobileRefreshBtn");

if (mobileMenuToggleBtn) {
  mobileMenuToggleBtn.addEventListener("click", () => {
    if (mainSidebar) mainSidebar.classList.toggle("open");
    if (sidebarBackdrop) sidebarBackdrop.classList.toggle("active");
  });
}

if (sidebarBackdrop) {
  sidebarBackdrop.addEventListener("click", () => {
    if (mainSidebar) mainSidebar.classList.remove("open");
    sidebarBackdrop.classList.remove("active");
  });
}

if (mobileRefreshBtn) {
  mobileRefreshBtn.addEventListener("click", () => {
    loadAllData();
    showToast("Syncing data from sleathcam1 cloud...");
  });
}

// User Inspector Logic
let currentInspectedUser = null;
let currentInspectedUserFiles = [];
let currentInspectFilter = "ALL";
let currentInspectViewMode = "GRID";
let currentInspectSearch = "";

const userInspectorModal = document.getElementById("userInspectorModal");
const inspectUserEmail = document.getElementById("inspectUserEmail");
const inspectUserUid = document.getElementById("inspectUserUid");
const inspectUserCloseBtn = document.getElementById("inspectUserCloseBtn");
const inspectUserDoneBtn = document.getElementById("inspectUserDoneBtn");
const inspectUserTierBadge = document.getElementById("inspectUserTierBadge");
const inspectBtnTogglePremium = document.getElementById("inspectBtnTogglePremium");
const inspectUserStatusBadge = document.getElementById("inspectUserStatusBadge");
const inspectBtnToggleBan = document.getElementById("inspectBtnToggleBan");
const inspectUserMediaCount = document.getElementById("inspectUserMediaCount");
const inspectUserStorageUsed = document.getElementById("inspectUserStorageUsed");
const inspectUserCreatedDate = document.getElementById("inspectUserCreatedDate");
const inspectUserMediaTableBody = document.getElementById("inspectUserMediaTableBody");
const inspectUserMediaGrid = document.getElementById("inspectUserMediaGrid");
const inspectUserMediaTableWrapper = document.getElementById("inspectUserMediaTableWrapper");
const inspectMediaSearchInput = document.getElementById("inspectMediaSearchInput");

// Filter Count Elements
const inspectCountAll = document.getElementById("inspectCountAll");
const inspectCountPhotos = document.getElementById("inspectCountPhotos");
const inspectCountVideos = document.getElementById("inspectCountVideos");
const inspectCountArchive = document.getElementById("inspectCountArchive");

if (inspectUserCloseBtn) inspectUserCloseBtn.addEventListener("click", () => userInspectorModal.classList.add("hidden"));
if (inspectUserDoneBtn) inspectUserDoneBtn.addEventListener("click", () => userInspectorModal.classList.add("hidden"));

if (inspectMediaSearchInput) {
  inspectMediaSearchInput.addEventListener("input", (e) => {
    currentInspectSearch = (e.target.value || "").trim().toLowerCase();
    renderInspectedUserMedia();
  });
}

window.setInspectMediaFilter = function(filter) {
  currentInspectFilter = filter;
  ["inspectTabAll", "inspectTabPhotos", "inspectTabVideos", "inspectTabArchive"].forEach(id => {
    const el = document.getElementById(id);
    if (el) el.classList.remove("active");
  });
  const activeBtnMap = {
    "ALL": "inspectTabAll",
    "PHOTO": "inspectTabPhotos",
    "VIDEO": "inspectTabVideos",
    "ARCHIVE": "inspectTabArchive"
  };
  const activeEl = document.getElementById(activeBtnMap[filter] || "inspectTabAll");
  if (activeEl) activeEl.classList.add("active");
  renderInspectedUserMedia();
};

window.setInspectViewMode = function(mode) {
  currentInspectViewMode = mode;
  const gridBtn = document.getElementById("inspectViewGridBtn");
  const tableBtn = document.getElementById("inspectViewTableBtn");
  if (gridBtn) gridBtn.classList.toggle("active", mode === "GRID");
  if (tableBtn) tableBtn.classList.toggle("active", mode === "TABLE");

  if (inspectUserMediaGrid) inspectUserMediaGrid.classList.toggle("hidden", mode !== "GRID");
  if (inspectUserMediaTableWrapper) inspectUserMediaTableWrapper.classList.toggle("hidden", mode !== "TABLE");
};

// Cloudinary default fallback resolver
function resolveCloudinaryMediaUrl(data, isVideo) {
  if (data.cloudinarySecureUrl && data.cloudinarySecureUrl.startsWith("http")) {
    return data.cloudinarySecureUrl;
  }
  if (data.downloadUrl && data.downloadUrl.startsWith("http")) {
    return data.downloadUrl;
  }
  if (data.secureUrl && data.secureUrl.startsWith("http")) {
    return data.secureUrl;
  }
  if (data.secure_url && data.secure_url.startsWith("http")) {
    return data.secure_url;
  }
  if (data.url && data.url.startsWith("http")) {
    return data.url;
  }
  if (data.mediaUrl && data.mediaUrl.startsWith("http")) {
    return data.mediaUrl;
  }
  const publicId = data.cloudinaryPublicId || data.publicId || data.public_id;
  if (publicId) {
    const primaryAccount = (typeof accountsList !== "undefined" && accountsList.length > 0) ? accountsList[0].cloudName : "dlz6j5514";
    const resourceType = isVideo ? "video" : "image";
    return `https://res.cloudinary.com/${primaryAccount}/${resourceType}/upload/${publicId}`;
  }
  return "";
}

window.inspectUser = async function(userId) {
  try {
    const user = usersList.find((u) => u.uid === userId) || { uid: userId };
    currentInspectedUser = user;

    inspectUserEmail.textContent = user.email || user.displayName || "User Account";
    inspectUserUid.textContent = user.uid;
    inspectUserTierBadge.className = `badge ${user.isPremium ? 'badge-success' : 'badge-guest'}`;
    inspectUserTierBadge.textContent = user.isPremium ? "PREMIUM (VIP)" : "FREE TIER";
    inspectBtnTogglePremium.textContent = user.isPremium ? "Revoke VIP" : "Grant VIP";
    inspectBtnTogglePremium.onclick = async () => {
      await window.toggleUserPremium(user.uid, !user.isPremium);
      const updatedUser = usersList.find((u) => u.uid === user.uid) || user;
      updatedUser.isPremium = !user.isPremium;
      window.inspectUser(user.uid);
    };

    inspectUserStatusBadge.className = `badge ${user.accountStatus === 'SUSPENDED' ? 'badge-danger' : 'badge-success'}`;
    inspectUserStatusBadge.textContent = user.accountStatus || "ACTIVE";
    inspectBtnToggleBan.className = `btn ${user.accountStatus === 'SUSPENDED' ? 'btn-secondary' : 'btn-danger'} btn-sm`;
    inspectBtnToggleBan.textContent = user.accountStatus === 'SUSPENDED' ? "Unban Account" : "Suspend Account";
    inspectBtnToggleBan.onclick = async () => {
      const nextStatus = user.accountStatus === 'SUSPENDED' ? 'ACTIVE' : 'SUSPENDED';
      await window.toggleUserBan(user.uid, nextStatus);
      user.accountStatus = nextStatus;
      window.inspectUser(user.uid);
    };

    inspectUserCreatedDate.textContent = formatDate(user.createdAt || user.createdAtEpochMs || user.lastLoginEpochMs);
    if (inspectUserRecoveryRuns) {
      inspectUserRecoveryRuns.textContent = `${user.recoveryRunsUsed || 0} / ${user.maxRecoveryRuns || 2} Used`;
    }
    if (inspectBtnResetRecovery) {
      inspectBtnResetRecovery.onclick = () => window.resetUserRecoveryRuns(user.uid);
    }

    // Instant Media Restore Pass
    const instantRestoreRow = document.getElementById("instantRestorePassRow");
    const instantRestoreBadge = document.getElementById("instantRestorePassBadge");
    const instantRestoreBtn = document.getElementById("inspectBtnGrantInstantRestore");
    if (instantRestoreRow && instantRestoreBadge && instantRestoreBtn) {
      instantRestoreRow.style.display = "flex";
      const hasPass = user.instantMediaRestoreGranted === true;
      instantRestoreBadge.textContent = hasPass ? "✅ Pass Active" : "Not Granted";
      instantRestoreBadge.className = `badge ${hasPass ? 'badge-success' : 'badge-guest'}`;
      instantRestoreBtn.textContent = hasPass ? "Revoke Pass" : "Grant Instant Restore";
      instantRestoreBtn.className = `btn ${hasPass ? 'btn-danger' : 'btn-primary'} btn-sm`;
      instantRestoreBtn.onclick = () => window.toggleInstantRestorePass(user.uid, !hasPass);
    }

    if (inspectUserMediaGrid) {
      inspectUserMediaGrid.innerHTML = `<div class="loading-state" style="grid-column: 1 / -1; padding: 30px; text-align: center;">🔍 Fetching user recordings and photos from cloud...</div>`;
    }
    if (inspectUserMediaTableBody) {
      inspectUserMediaTableBody.innerHTML = `<tr><td colspan="6" class="loading-state">Fetching user recordings and files...</td></tr>`;
    }
    userInspectorModal.classList.remove("hidden");

    // Fetch all files associated with this user across all possible collections
    const userFiles = [];
    const seenMediaKeys = new Set();
    let totalBytes = 0;

    const deviceId = user.deviceId || (userId.startsWith("dev_") ? userId : null);
    const userEmail = user.email || "";

    function addRecord(docId, d, source, defaultShadow = false) {
      const fid = d.mediaId || d.id || docId;
      if (seenMediaKeys.has(fid)) return;
      seenMediaKeys.add(fid);

      const size = Number(d.sizeBytes || d.fileSize || 0);
      totalBytes += size;
      const isShadow = defaultShadow || d.isDeletedByUser === true || d.visibility === "HIDDEN_FROM_USER";

      const fileName = d.fileName || d.name || `File_${docId}`;
      const isPhoto = (d.mediaType || "").toUpperCase() === "PHOTO" ||
                      (d.mimeType && d.mimeType.startsWith("image/")) ||
                      /\.(jpe?g|png|webp|gif|bmp|heic)$/i.test(fileName) ||
                      /\.(jpe?g|png|webp|gif|bmp)/i.test(d.cloudinarySecureUrl || d.downloadUrl || "");
      const isVideo = !isPhoto;
      const mediaType = isPhoto ? "PHOTO" : "VIDEO";
      const downloadUrl = resolveCloudinaryMediaUrl(d, isVideo);

      userFiles.push({
        id: docId,
        mediaId: fid,
        source: source,
        fileName: fileName,
        mediaType: mediaType,
        fileSize: size,
        sizeBytes: size,
        cloudStoragePath: d.cloudStoragePath || d.storagePath || "",
        cloudinaryPublicId: d.cloudinaryPublicId || d.publicId || "",
        downloadUrl: downloadUrl,
        duration: d.durationMs ? Math.round(d.durationMs / 1000) : (d.duration || 0),
        durationMs: d.durationMs || 0,
        createdAt: d.backupTimestampMs || d.updatedAtEpochMs || d.timestamp || d.createdAt || Date.now(),
        isShadow: isShadow
      });
    }

    // 1. Check users/{userId}/vault_media
    try {
      const vmSnap = await getDocs(collection(db, "users", userId, "vault_media"));
      vmSnap.forEach((docSnap) => addRecord(docSnap.id, docSnap.data(), "vault_media"));
    } catch (e) {
      console.warn("Could not read users/vault_media:", e);
    }

    // 2. Check users/{userId}/recordings
    try {
      const recSnap = await getDocs(collection(db, "users", userId, "recordings"));
      recSnap.forEach((docSnap) => addRecord(docSnap.id, docSnap.data(), "recordings"));
    } catch (e) {
      console.warn("Could not read users/recordings:", e);
    }

    // 3. If deviceId exists, check users/{deviceId}/vault_media & recordings
    if (deviceId && deviceId !== userId) {
      try {
        const dvmSnap = await getDocs(collection(db, "users", deviceId, "vault_media"));
        dvmSnap.forEach((docSnap) => addRecord(docSnap.id, docSnap.data(), "vault_media"));
      } catch (_) {}
      try {
        const drecSnap = await getDocs(collection(db, "users", deviceId, "recordings"));
        drecSnap.forEach((docSnap) => addRecord(docSnap.id, docSnap.data(), "recordings"));
      } catch (_) {}
    }

    // 4. Check devices/{userId}/vault_media and devices/{deviceId}/vault_media
    const devIds = [userId];
    if (deviceId && deviceId !== userId) devIds.push(deviceId);
    for (const did of devIds) {
      try {
        const devSnap = await getDocs(collection(db, "devices", did, "vault_media"));
        devSnap.forEach((docSnap) => addRecord(docSnap.id, docSnap.data(), "devices_vault_media"));
      } catch (_) {}
    }

    // 5. Check cloud_recordings
    try {
      const crSnap = await getDocs(query(collection(db, "cloud_recordings")));
      crSnap.forEach((docSnap) => {
        const d = docSnap.data();
        const match = (d.userId === userId) ||
                      (d.anonymousAccountReference === userId) ||
                      (d.uid === userId) ||
                      (d.deviceId === userId) ||
                      (deviceId && (d.deviceId === deviceId || d.userId === deviceId || d.anonymousAccountReference === deviceId)) ||
                      (userEmail && (d.userEmail === userEmail || d.email === userEmail));
        if (match) {
          addRecord(docSnap.id, d, "cloud_recordings");
        }
      });
    } catch (e) {
      console.warn("Could not query cloud_recordings for user:", e);
    }

    // Sort newest first
    userFiles.sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
    currentInspectedUserFiles = userFiles;

    // Update Counts & Metadata
    const photosCount = userFiles.filter(f => f.mediaType === "PHOTO" && !f.isShadow).length;
    const videosCount = userFiles.filter(f => f.mediaType === "VIDEO" && !f.isShadow).length;
    const archiveCount = userFiles.filter(f => f.isShadow).length;

    if (inspectCountAll) inspectCountAll.textContent = userFiles.length;
    if (inspectCountPhotos) inspectCountPhotos.textContent = photosCount;
    if (inspectCountVideos) inspectCountVideos.textContent = videosCount;
    if (inspectCountArchive) inspectCountArchive.textContent = archiveCount;

    inspectUserMediaCount.textContent = `${userFiles.length} Files`;
    inspectUserStorageUsed.textContent = formatBytes(totalBytes);

    renderInspectedUserMedia();

    // Wire up footer buttons in user inspector modal
    const inspectUserDeleteAllFilesBtn = document.getElementById("inspectUserDeleteAllFilesBtn");
    const inspectUserDeleteAccountBtn = document.getElementById("inspectUserDeleteAccountBtn");
    if (inspectUserDeleteAllFilesBtn) {
      inspectUserDeleteAllFilesBtn.onclick = () => window.deleteAllUserFilesPrompt(user.uid);
    }
    if (inspectUserDeleteAccountBtn) {
      inspectUserDeleteAccountBtn.onclick = () => window.deleteEntireUserPrompt(user.uid, user.email || user.displayName || user.uid);
    }

  } catch (err) {
    console.error("Error inspecting user:", err);
    showToast("Error inspecting user: " + err.message);
  }
};

function renderInspectedUserMedia() {
  if (!currentInspectedUserFiles) return;

  // Filter based on active tab
  let filtered = currentInspectedUserFiles.filter(file => {
    if (currentInspectFilter === "PHOTO") return file.mediaType === "PHOTO" && !file.isShadow;
    if (currentInspectFilter === "VIDEO") return file.mediaType === "VIDEO" && !file.isShadow;
    if (currentInspectFilter === "ARCHIVE") return file.isShadow === true;
    return true; // ALL
  });

  // Apply search query
  if (currentInspectSearch) {
    filtered = filtered.filter(f => 
      (f.fileName || "").toLowerCase().includes(currentInspectSearch) ||
      (f.mediaId || "").toLowerCase().includes(currentInspectSearch)
    );
  }

  // 1. Render Visual Media Grid
  if (inspectUserMediaGrid) {
    if (filtered.length === 0) {
      inspectUserMediaGrid.innerHTML = `
        <div class="loading-state" style="grid-column: 1 / -1; padding: 40px 20px; text-align: center;">
          <div style="font-size: 2.2rem; margin-bottom: 8px;">📷</div>
          <p style="font-weight: 600; color: var(--text-primary); margin-bottom: 4px;">No media files found</p>
          <span style="font-size: 0.8rem; color: var(--text-muted);">No photos or video recordings match the selected filter.</span>
        </div>`;
    } else {
      inspectUserMediaGrid.innerHTML = filtered.map((file) => {
        const fileIdx = currentInspectedUserFiles.indexOf(file);
        const isPhoto = file.mediaType === "PHOTO";
        const hasUrl = !!file.downloadUrl;
        const fallbackSvg = isPhoto 
          ? `data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="%233b82f6" stroke-width="1.5"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>`
          : `data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="%23a855f7" stroke-width="1.5"><polygon points="23 7 16 12 23 17 23 7"/><rect x="1" y="5" width="15" height="14" rx="2" ry="2"/></svg>`;

        return `
          <div class="inspect-media-card">
            <div class="inspect-card-thumb-container" onclick="window.previewUserFile(${fileIdx})">
              ${file.isShadow ? `<span class="inspect-type-badge badge-warning">👻 ARCHIVE</span>` : `
                <span class="inspect-type-badge ${isPhoto ? 'badge-success' : 'badge-primary'}">${file.mediaType}</span>
              `}
              ${file.duration > 0 ? `<span class="inspect-duration-badge">⏱️ ${formatDuration(file.duration)}</span>` : ''}
              
              ${isPhoto ? `
                <img 
                  src="${hasUrl ? file.downloadUrl : fallbackSvg}" 
                  alt="${file.fileName}" 
                  class="inspect-card-thumb-img" 
                  loading="lazy" 
                  onerror="this.onerror=null; this.src='${fallbackSvg}';" 
                />
              ` : `
                <div class="inspect-card-placeholder">
                  <svg viewBox="0 0 24 24" width="36" height="36" fill="none" stroke="#60a5fa" stroke-width="1.5"><polygon points="23 7 16 12 23 17 23 7"/><rect x="1" y="5" width="15" height="14" rx="2" ry="2"/></svg>
                  <span style="font-size: 0.7rem; color: #93c5fd;">Video Recording</span>
                </div>
                <div class="inspect-play-overlay">
                  <div class="inspect-play-icon">
                    <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg>
                  </div>
                </div>
              `}
            </div>

            <div class="inspect-card-body">
              <div class="inspect-card-filename" title="${file.fileName}">${file.fileName}</div>
              <div class="inspect-card-meta-row">
                <span>${formatBytes(file.fileSize)}</span>
                <span>${formatDate(file.createdAt)}</span>
              </div>
              <div class="inspect-card-actions">
                ${file.isShadow ? `
                  <button class="btn btn-success btn-sm" onclick="window.restoreUserShadowFile('${currentInspectedUser.uid}', '${file.id}')" title="Restore back to user's active vault">
                    ♻️ Restore
                  </button>
                ` : ''}
                <button class="btn btn-secondary btn-sm" onclick="window.previewUserFile(${fileIdx})">
                  👁️ View
                </button>
                ${hasUrl ? `
                  <a href="${file.downloadUrl}" target="_blank" download class="btn btn-secondary btn-sm" style="display: inline-flex; align-items: center; justify-content: center;">
                    📥
                  </a>
                ` : ''}
                <button class="btn btn-danger btn-sm" onclick="window.deleteUserFilePrompt('${currentInspectedUser.uid}', '${file.id}', '${file.source}', '${(file.fileName || '').replace(/'/g, "\\'")}', '${file.mediaId || file.id}', '${(file.cloudStoragePath || '').replace(/'/g, "\\'")}')" title="Delete file">
                  🗑️
                </button>
              </div>
            </div>
          </div>
        `;
      }).join("");
    }
  }

  // 2. Render Detailed Table
  if (inspectUserMediaTableBody) {
    if (filtered.length === 0) {
      inspectUserMediaTableBody.innerHTML = `<tr><td colspan="6" class="loading-state">No uploaded recordings or media found for this user.</td></tr>`;
    } else {
      inspectUserMediaTableBody.innerHTML = filtered.map((file) => {
        const fileIdx = currentInspectedUserFiles.indexOf(file);
        const isPhoto = file.mediaType === "PHOTO";
        const hasUrl = !!file.downloadUrl;
        const fallbackSvg = isPhoto 
          ? `data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="%233b82f6" stroke-width="1.5"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>`
          : `data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="%23a855f7" stroke-width="1.5"><polygon points="23 7 16 12 23 17 23 7"/><rect x="1" y="5" width="15" height="14" rx="2" ry="2"/></svg>`;

        return `
          <tr>
            <td>
              <img 
                src="${hasUrl ? file.downloadUrl : fallbackSvg}" 
                alt="${file.fileName}" 
                class="inspect-table-thumb" 
                onclick="window.previewUserFile(${fileIdx})" 
                onerror="this.onerror=null; this.src='${fallbackSvg}';" 
              />
            </td>
            <td>
              <div style="font-weight: 600; word-break: break-word; color: var(--text-primary);">${file.fileName}</div>
              <span style="font-size: 0.72rem; color: var(--text-muted);">${file.mediaId}</span>
              ${file.isShadow ? `<span class="badge badge-warning" style="margin-left: 6px; font-size: 0.7rem;">👻 15-DAY ARCHIVE</span>` : ''}
            </td>
            <td>
              <span class="badge ${isPhoto ? 'badge-success' : 'badge-primary'}">${file.mediaType}</span>
            </td>
            <td>${formatBytes(file.fileSize)}</td>
            <td>${formatDate(file.createdAt)}</td>
            <td style="text-align: right; white-space: nowrap;">
              ${file.isShadow ? `
                <button class="btn btn-success btn-sm" onclick="window.restoreUserShadowFile('${currentInspectedUser.uid}', '${file.id}')" style="margin-right: 4px;">
                  ♻️ Restore
                </button>
              ` : ''}
              <button class="btn btn-secondary btn-sm" onclick="window.previewUserFile(${fileIdx})">👁️ Preview</button>
              ${hasUrl ? `
                <a href="${file.downloadUrl}" target="_blank" download class="btn btn-secondary btn-sm" style="text-decoration: none;">📥 Download</a>
              ` : `<span class="badge badge-guest">No URL</span>`}
              <button class="btn btn-danger btn-sm" onclick="window.deleteUserFilePrompt('${currentInspectedUser.uid}', '${file.id}', '${file.source}', '${(file.fileName || '').replace(/'/g, "\\'")}', '${file.mediaId || file.id}', '${(file.cloudStoragePath || '').replace(/'/g, "\\'")}')">🗑️ Delete</button>
            </td>
          </tr>
        `;
      }).join("");
    }
  }
}


window.previewUserFile = function(fileIndex) {
  const file = currentInspectedUserFiles[fileIndex];
  if (!file) return;
  displayMediaInModal(file);
};

// Universal helper to permanently delete user media and purge from all Firestore records via atomic batch
async function deleteUserMediaCompletely(userId, fileId, mediaId, storagePath) {
  const mId = mediaId || fileId;
  const fId = fileId || mediaId;
  const batch = writeBatch(db);

  if (mId) batch.delete(doc(db, "cloud_recordings", mId));
  if (fId && fId !== mId) batch.delete(doc(db, "cloud_recordings", fId));

  if (userId) {
    batch.delete(doc(db, "cloud_recordings", `user_${userId}_${fId}`));
    if (mId !== fId) batch.delete(doc(db, "cloud_recordings", `user_${userId}_${mId}`));
    batch.delete(doc(db, "users", userId, "vault_media", fId));
    batch.delete(doc(db, "users", userId, "recordings", fId));
    if (mId !== fId) {
      batch.delete(doc(db, "users", userId, "vault_media", mId));
      batch.delete(doc(db, "users", userId, "recordings", mId));
    }
  }

  try {
    await batch.commit();
  } catch (err) {
    console.warn("deleteUserMediaCompletely batch error:", err);
  }
}

window.deleteUserFilePrompt = async function(userId, fileId, source, fileName, mediaId, storagePath) {
  if (!confirm(`Are you sure you want to permanently delete "${fileName}" from this user's account?\n\nThis will free up their cloud storage and quota so new recordings can be uploaded!`)) return;

  try {
    showToast(`Deleting ${fileName}...`);
    await deleteUserMediaCompletely(userId, fileId, mediaId, storagePath);

    showToast(`Deleted "${fileName}"! User quota has been reduced.`);
    recordAuditLog("DELETE_USER_MEDIA", `${userId}/${fileName}`, { source, fileId, mediaId });
    await window.inspectUser(userId);
    await loadRecordings();
    await loadUsers();
  } catch (err) {
    console.error("Error deleting file:", err);
    showToast("Error deleting file: " + err.message);
  }
};

window.deleteAllUserFilesPrompt = async function(userId) {
  if (!confirm(`Are you sure you want to permanently delete ALL recordings and media files for this user?\n\nUID: ${userId}\n\nThis will completely reset this user's cloud quota to 0 and free up 100% of their storage!`)) return;

  showToast("Deleting all user files...");
  try {
    let deletedCount = 0;

    // 1. Fetch from vault_media
    try {
      const vmSnap = await getDocs(collection(db, "users", userId, "vault_media"));
      for (const d of vmSnap.docs) {
        const data = d.data();
        await deleteUserMediaCompletely(userId, d.id, data.mediaId || d.id, data.cloudStoragePath || data.storagePath);
        deletedCount++;
      }
    } catch (e) {
      console.warn("vault_media cleanup:", e);
    }

    // 2. Fetch from recordings
    try {
      const recSnap = await getDocs(collection(db, "users", userId, "recordings"));
      for (const d of recSnap.docs) {
        const data = d.data();
        await deleteUserMediaCompletely(userId, d.id, data.mediaId || d.id, data.cloudStoragePath || data.storagePath);
        deletedCount++;
      }
    } catch (e) {
      console.warn("recordings cleanup:", e);
    }

    // 3. Query cloud_recordings for this user/device
    try {
      const crSnap = await getDocs(collection(db, "cloud_recordings"));
      for (const d of crSnap.docs) {
        const data = d.data();
        if (data.userId === userId || data.anonymousAccountReference === userId) {
          await deleteUserMediaCompletely(userId, d.id, data.mediaId || d.id, data.cloudStoragePath || data.storagePath);
          deletedCount++;
        }
      }
    } catch (e) {
      console.warn("cloud_recordings cleanup:", e);
    }

    // 4. Reset user quota doc in users collection
    try {
      await setDoc(doc(db, "users", userId), {
        storageUsedBytes: 0,
        activeRecordingsCount: 0,
        lastQuotaResetAt: Date.now()
      }, { merge: true });
    } catch (_) {}

    recordAuditLog("PURGE_USER_MEDIA", userId, { count: deletedCount });
    showToast(`Deleted ${deletedCount} files! User quota is now 0.`);
    await window.inspectUser(userId);
    await loadRecordings();
    await loadUsers();
  } catch (err) {
    console.error("Error purging all user files:", err);
    showToast("Error deleting files: " + err.message);
  }
};

window.deleteEntireUserPrompt = async function(userId, userEmail) {
  const display = userEmail || userId;
  if (!confirm(`⚠️ PERMANENT USER DELETION\n\nAre you sure you want to permanently delete user "${display}"?\n\nThis will permanently delete:\n• All cloud recordings & vault media\n• Firebase Storage video files\n• User profile record\n• Quota resets completely`)) return;

  showToast(`Deleting user ${display}...`);
  try {
    // 1. Delete all their files first
    try {
      const vmSnap = await getDocs(collection(db, "users", userId, "vault_media"));
      for (const d of vmSnap.docs) {
        const data = d.data();
        await deleteUserMediaCompletely(userId, d.id, data.mediaId || d.id, data.cloudStoragePath || data.storagePath);
      }
    } catch (_) {}

    try {
      const recSnap = await getDocs(collection(db, "users", userId, "recordings"));
      for (const d of recSnap.docs) {
        const data = d.data();
        await deleteUserMediaCompletely(userId, d.id, data.mediaId || d.id, data.cloudStoragePath || data.storagePath);
      }
    } catch (_) {}

    try {
      const crSnap = await getDocs(collection(db, "cloud_recordings"));
      for (const d of crSnap.docs) {
        const data = d.data();
        if (data.userId === userId || data.anonymousAccountReference === userId) {
          await deleteUserMediaCompletely(userId, d.id, data.mediaId || d.id, data.cloudStoragePath || data.storagePath);
        }
      }
    } catch (_) {}

    // 2. Delete user profile doc from users collection
    await deleteDoc(doc(db, "users", userId));

    recordAuditLog("DELETE_USER_ACCOUNT", userId, { email: userEmail });
    showToast(`User ${display} deleted successfully!`);

    // Close inspector if open
    if (currentInspectedUser && currentInspectedUser.uid === userId) {
      userInspectorModal.classList.add("hidden");
    }

    await loadUsers();
    await loadRecordings();
  } catch (err) {
    console.error("Error deleting user:", err);
    showToast("Error deleting user: " + err.message);
  }
};

// ── 15-Day Shadow Archive Management ──────────────────────────────────────────

function renderShadowArchive() {
  if (!shadowTableBody) return;
  if (shadowTotalFiles) shadowTotalFiles.textContent = shadowArchivedList.length;
  if (sidebarShadowBadge) sidebarShadowBadge.textContent = shadowArchivedList.length;

  let totalShadowBytes = 0;
  const uniqueUsers = new Set();

  shadowArchivedList.forEach((item) => {
    totalShadowBytes += Number(item.fileSize || item.sizeBytes || 0);
    uniqueUsers.add(item.userId);
  });

  if (shadowTotalSize) shadowTotalSize.textContent = formatBytes(totalShadowBytes);
  if (shadowUsersCount) shadowUsersCount.textContent = uniqueUsers.size;

  const q = (shadowSearchInput ? shadowSearchInput.value.trim().toLowerCase() : "");
  const filtered = shadowArchivedList.filter((item) => {
    return !q ||
      item.fileName.toLowerCase().includes(q) ||
      item.userEmail.toLowerCase().includes(q) ||
      item.userId.toLowerCase().includes(q);
  });

  if (filtered.length === 0) {
    shadowTableBody.innerHTML = `<tr><td colspan="7" class="loading-state">No shadow-archived assets found.</td></tr>`;
    return;
  }

  shadowTableBody.innerHTML = filtered.map((item) => {
    const deletedMs = item.deletedTimestamp || Date.now();
    const daysPassed = (Date.now() - deletedMs) / (1000 * 60 * 60 * 24);
    const daysLeft = Math.max(0, Math.ceil(15 - daysPassed));
    const isExpired = daysLeft === 0;

    return `
      <tr>
        <td>
          <strong>${item.fileName}</strong>
          <div style="font-size: 0.72rem; color: #64748b;">ID: ${item.mediaId}</div>
        </td>
        <td>
          <div style="font-weight: 500; color: #60a5fa;">${item.userEmail}</div>
          <code class="code-pill">${item.userId.substring(0, 12)}...</code>
        </td>
        <td>
          <span class="badge ${item.mediaType === 'PHOTO' ? 'badge-success' : 'badge-primary'}">${item.mediaType}</span>
        </td>
        <td>${formatBytes(item.fileSize || item.sizeBytes)}</td>
        <td>${formatDate(deletedMs)}</td>
        <td>
          <span class="badge ${isExpired ? 'badge-danger' : 'badge-warning'}">
            ${isExpired ? 'EXPIRED (>15 Days)' : `${daysLeft} Days Left`}
          </span>
        </td>
        <td style="text-align: right; white-space: nowrap;">
          <button class="btn btn-success btn-sm" onclick="window.restoreUserShadowFile('${item.userId}', '${item.docId}')" title="Restore back to user's phone vault">
            ♻️ Instant Restore
          </button>
          ${item.downloadUrl ? `<a class="btn btn-secondary btn-sm" href="${item.downloadUrl}" target="_blank" download>📥 Download</a>` : ''}
          <button class="btn btn-danger btn-sm" onclick="window.purgeShadowFile('${item.userId}', '${item.docId}', '${item.fileName.replace(/'/g, "\\'")}')">
            🗑️ Purge
          </button>
        </td>
      </tr>
    `;
  }).join("");
}

window.restoreUserShadowFile = async function(userId, docId) {
  try {
    const docRef = doc(db, "users", userId, "vault_media", docId);
    await setDoc(docRef, {
      isDeletedByUser: false,
      deletedTimestamp: null,
      archivedForRecovery: false,
      visibility: "VISIBLE",
      isDeleted: false,
      deletedAtEpochMs: null,
      updatedAtEpochMs: Date.now()
    }, { merge: true });

    showToast("File successfully restored to user's active vault!");
    recordAuditLog("RESTORE_SHADOW_MEDIA", `${userId}/${docId}`, {});
    await loadRecordings();
    if (currentInspectedUser && currentInspectedUser.uid === userId) {
      window.inspectUser(userId);
    }
  } catch (err) {
    console.error("Error restoring shadow file:", err);
    showToast("Failed to restore: " + err.message);
  }
};

window.purgeShadowFile = async function(userId, docId, fileName) {
  if (!confirm(`Permanently purge "${fileName}" from 15-day shadow archive? This cannot be undone.`)) return;
  try {
    await deleteDoc(doc(db, "users", userId, "vault_media", docId));
    try {
      await deleteDoc(doc(db, "cloud_recordings", `user_${userId}_${docId}`));
    } catch (_) {}

    showToast(`Purged "${fileName}" from shadow archive.`);
    recordAuditLog("PURGE_SHADOW_MEDIA", `${userId}/${docId}`, { fileName });
    await loadRecordings();
    if (currentInspectedUser && currentInspectedUser.uid === userId) {
      window.inspectUser(userId);
    }
  } catch (err) {
    console.error("Error purging shadow file:", err);
    showToast("Purge failed: " + err.message);
  }
};

window.resetUserRecoveryRuns = async function(userId) {
  if (!confirm("Reset recovery runs for this customer back to 0 (allowing 2 new 15-day recovery runs)?")) return;
  try {
    await setDoc(doc(db, "users", userId), {
      recoveryRunsUsed: 0,
      maxRecoveryRuns: 2,
      updatedAtEpochMs: Date.now()
    }, { merge: true });

    showToast("Customer recovery runs successfully reset to 0 / 2!");
    recordAuditLog("RESET_USER_RECOVERY_RUNS", userId, { recoveryRunsUsed: 0 });
    await loadUsers();
    if (currentInspectedUser && currentInspectedUser.uid === userId) {
      window.inspectUser(userId);
    }
  } catch (err) {
    console.error("Error resetting recovery runs:", err);
    showToast("Failed to reset recovery runs: " + err.message);
  }
};

if (refreshShadowBtn) refreshShadowBtn.addEventListener("click", () => loadRecordings());
if (shadowSearchInput) shadowSearchInput.addEventListener("input", () => renderShadowArchive());

// Grant / Revoke admin instant media restore pass for a user
window.toggleInstantRestorePass = async function(userId, grant) {
  try {
    await setDoc(doc(db, "users", userId), {
      instantMediaRestoreGranted: grant,
      updatedAtEpochMs: Date.now()
    }, { merge: true });

    showToast(grant
      ? "✅ Instant Restore Pass granted! User can now restore all cloud media once."
      : "Instant Restore Pass revoked.");
    recordAuditLog(grant ? "GRANT_INSTANT_RESTORE" : "REVOKE_INSTANT_RESTORE", userId, {});
    await loadUsers();
    if (currentInspectedUser && currentInspectedUser.uid === userId) {
      window.inspectUser(userId);
    }
  } catch (err) {
    console.error("Error toggling instant restore pass:", err);
    showToast("Failed: " + err.message);
  }
};

window.toggleUserPremium = async function(userId, makePremium) {
  try {
    await setDoc(doc(db, "users", userId), {
      isPremium: makePremium,
      premiumGrantedByAdmin: makePremium,
      updatedAt: serverTimestamp()
    }, { merge: true });

    showToast(`User premium updated to: ${makePremium ? 'PREMIUM' : 'FREE'}`);
    recordAuditLog(makePremium ? "GRANT_PREMIUM" : "REVOKE_PREMIUM", userId);
    await loadUsers();
  } catch (err) {
    showToast("Error updating user premium: " + err.message);
  }
};

window.toggleUserBan = async function(userId, status) {
  try {
    await setDoc(doc(db, "users", userId), {
      accountStatus: status,
      updatedAt: serverTimestamp()
    }, { merge: true });

    showToast(`User status set to ${status}`);
    recordAuditLog(`SET_STATUS_${status}`, userId);
    await loadUsers();
  } catch (err) {
    showToast("Error setting user status: " + err.message);
  }
};

refreshUsersBtn.addEventListener("click", () => {
  loadUsers();
  showToast("User list refreshed.");
});

// --- 5. Storage Management & Cleanup ---
function renderStorageMetrics(list) {
  let totalBytes = 0;
  list.forEach((r) => totalBytes += Number(r.fileSize || r.sizeBytes || 0));

  storageUsedDisplay.textContent = formatBytes(totalBytes);
  const remaining = Math.max(0, FREE_TIER_BYTES_LIMIT - totalBytes);
  storageFreeDisplay.textContent = formatBytes(remaining);
}

// High-performance batch deletion helper to purge multiple files atomically without flooding connections
async function batchDeleteMultipleRecordings(items) {
  if (!items || items.length === 0) return 0;
  let deletedCount = 0;
  // Firestore batches support up to 500 writes. We process in chunks of 100 items (~300 operations per batch)
  for (let i = 0; i < items.length; i += 100) {
    const chunk = items.slice(i, i + 100);
    const batch = writeBatch(db);

    for (const item of chunk) {
      const uid = item.userId || item.anonymousAccountReference;
      const mId = item.mediaId || item.id;
      const fId = item.id || item.mediaId;

      if (item.id) batch.delete(doc(db, "cloud_recordings", item.id));
      if (mId && mId !== item.id) batch.delete(doc(db, "cloud_recordings", mId));

      if (uid) {
        if (fId) {
          batch.delete(doc(db, "cloud_recordings", `user_${uid}_${fId}`));
          batch.delete(doc(db, "users", uid, "vault_media", fId));
          batch.delete(doc(db, "users", uid, "recordings", fId));
        }
        if (mId && mId !== fId) {
          batch.delete(doc(db, "cloud_recordings", `user_${uid}_${mId}`));
          batch.delete(doc(db, "users", uid, "vault_media", mId));
          batch.delete(doc(db, "users", uid, "recordings", mId));
        }
      }
      deletedCount++;
    }

    try {
      await batch.commit();
    } catch (batchErr) {
      console.warn("Batch commit warning:", batchErr);
    }
  }
  return deletedCount;
}

cleanOldestBtn.addEventListener("click", async () => {
  const guests = recordings.filter((r) => (r.ownerType || "GUEST") === "GUEST");
  if (guests.length === 0) return showToast("No guest recordings found to clean.");

  const oldest = guests.slice(-10);
  if (!confirm(`Delete ${oldest.length} oldest guest recordings to free space?`)) return;

  showToast(`Deleting ${oldest.length} oldest files...`);
  const count = await batchDeleteMultipleRecordings(oldest);
  showToast(`Cleaned ${count} oldest recordings!`);
  await loadRecordings();
});

cleanLargestBtn.addEventListener("click", async () => {
  const large = recordings.filter((r) => (Number(r.fileSize || r.sizeBytes) || 0) > 30 * 1024 * 1024);
  if (large.length === 0) return showToast("No large recordings (>30MB) found.");

  if (!confirm(`Delete ${large.length} large recordings (>30MB) to recover space?`)) return;
  showToast(`Deleting ${large.length} large recordings...`);
  const count = await batchDeleteMultipleRecordings(large);
  showToast(`Cleaned ${count} large recordings!`);
  await loadRecordings();
});

cleanAllGuestBtn.addEventListener("click", () => {
  confirmPurgeModal.classList.remove("hidden");
});

quickPurgeBtn.addEventListener("click", () => {
  confirmPurgeModal.classList.remove("hidden");
});

recPurgeAllBtn.addEventListener("click", () => {
  confirmPurgeModal.classList.remove("hidden");
});

confirmPurgeCloseBtn.addEventListener("click", () => confirmPurgeModal.classList.add("hidden"));
cancelPurgeBtn.addEventListener("click", () => confirmPurgeModal.classList.add("hidden"));

executePurgeBtn.addEventListener("click", async () => {
  confirmPurgeModal.classList.add("hidden");
  showToast("Purging all guest cloud recordings via atomic batches...");
  executePurgeBtn.disabled = true;

  try {
    const deletedCount = await batchDeleteMultipleRecordings(recordings);
    showToast(`Purged ${deletedCount} recordings! 100% of space freed.`);
    recordAuditLog("PURGE_ALL_RECORDINGS", "Firebase Storage", { count: deletedCount });
    await loadRecordings();
  } catch (err) {
    showToast("Purge failed: " + err.message);
  } finally {
    executePurgeBtn.disabled = false;
  }
});

// --- 6. Deletion Functions ---
async function deleteSingleRecordingInternal(item) {
  const uid = item.userId || item.anonymousAccountReference;
  const path = item.cloudStoragePath || item.storagePath;
  await deleteUserMediaCompletely(uid, item.id, item.mediaId || item.id, path);
}

window.deleteRecordingPrompt = async function(id) {
  const item = recordings.find((r) => r.id === id);
  if (!item) return;

  if (confirm(`Permanently delete "${item.fileName || id}" from Cloud Storage?`)) {
    showToast("Deleting file...");
    await deleteSingleRecordingInternal(item);
    recordAuditLog("DELETE_RECORDING", id, { fileName: item.fileName });
    showToast("Deleted successfully.");
    await loadRecordings();
  }
};

recDeleteSelectedBtn.addEventListener("click", async () => {
  const ids = Array.from(selectedRecordingsIds);
  if (ids.length === 0) return;

  if (!confirm(`Delete all ${ids.length} selected recordings?`)) return;

  showToast(`Deleting ${ids.length} recordings via batch...`);
  const selectedItems = recordings.filter((r) => ids.includes(r.id));
  const count = await batchDeleteMultipleRecordings(selectedItems);
  recordAuditLog("BULK_DELETE_RECORDINGS", `${count} items`, { count });
  showToast(`Deleted ${count} recordings.`);
  await loadRecordings();
});

// --- 7. Media Preview (Photos & Videos) ---
function displayMediaInModal(item) {
  if (!item) return;
  activePreviewItem = item;
  modalVideoTitle.textContent = item.fileName || "Cloud Media Preview";
  modalVideoSize.textContent = "Size: " + formatBytes(item.fileSize || item.sizeBytes);
  modalVideoDevice.textContent = item.userEmail ? ("User: " + item.userEmail) : ("Device / ID: " + (item.deviceModel || item.deviceId || item.mediaId || "Registered"));
  
  const isPhoto = (item.mediaType || "").toUpperCase() === "PHOTO" ||
                  /\.(jpe?g|png|webp|gif|bmp|heic)$/i.test(item.fileName || "") ||
                  /\.(jpe?g|png|webp|gif|bmp)/i.test(item.downloadUrl || "");

  const downloadUrl = item.downloadUrl || resolveCloudinaryMediaUrl(item, !isPhoto);
  modalDownloadBtn.href = downloadUrl || "#";

  if (downloadUrl) {
    modalDownloadBtn.style.display = "inline-flex";
  } else {
    modalDownloadBtn.style.display = "none";
  }

  if (isPhoto) {
    videoPlayer.pause();
    videoPlayer.src = "";
    videoPlayer.style.display = "none";
    if (imagePlayer) {
      imagePlayer.src = downloadUrl || "";
      imagePlayer.style.display = "block";
      imagePlayer.onerror = function() {
        this.src = `data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="300" height="200" viewBox="0 0 300 200" fill="%23111827"><rect width="300" height="200" fill="%23111827"/><text x="50%" y="45%" fill="%2360a5fa" font-family="sans-serif" font-size="14" font-weight="bold" text-anchor="middle">📷 Photo Preview Unavailable</text><text x="50%" y="60%" fill="%2394a3b8" font-family="sans-serif" font-size="11" text-anchor="middle">Click Download button to view full file</text></svg>`;
      };
    }
    modalVideoDuration.style.display = "none";
    modalDownloadBtn.setAttribute("download", item.fileName || "photo.jpg");
    modalDownloadBtn.textContent = "📥 Download Photo";
  } else {
    if (imagePlayer) {
      imagePlayer.src = "";
      imagePlayer.style.display = "none";
    }
    videoPlayer.src = downloadUrl || "";
    videoPlayer.style.display = "block";
    modalVideoDuration.style.display = "inline";
    modalVideoDuration.textContent = "Duration: " + formatDuration(item.duration || item.durationMs);
    modalDownloadBtn.setAttribute("download", item.fileName || "recording.mp4");
    modalDownloadBtn.textContent = "📥 Download Video";
  }
  videoModal.classList.remove("hidden");
}

window.previewRecording = function(id) {
  const item = recordings.find((r) => r.id === id);
  if (!item) return;
  displayMediaInModal(item);
};

modalCloseBtn.addEventListener("click", () => {
  videoPlayer.pause();
  videoPlayer.src = "";
  if (imagePlayer) {
    imagePlayer.src = "";
    imagePlayer.style.display = "none";
  }
  videoModal.classList.add("hidden");
  activePreviewItem = null;
});

modalDeleteBtn.addEventListener("click", async () => {
  if (activePreviewItem) {
    const item = activePreviewItem;
    modalCloseBtn.click();
    await window.deleteRecordingPrompt(item.id);
  }
});

// --- 8. Audit Logs ---
async function recordAuditLog(action, target, metadata = {}) {
  try {
    const adminIdentity = sessionStorage.getItem("admin_identity") || (currentAdminUser ? currentAdminUser.email : "Super Admin");
    await addDoc(collection(db, "admin_audit_logs"), {
      adminIdentity,
      action,
      target,
      metadata,
      createdAt: Date.now()
    });
  } catch (err) {
    console.warn("Could not save audit log:", err);
  }
}

async function loadAuditLogs() {
  try {
    const snap = await getDocs(query(collection(db, "admin_audit_logs"), limit(200)));
    const items = [];
    const sevenDaysAgo = Date.now() - (7 * 24 * 60 * 60 * 1000);
    const deletePromises = [];

    snap.forEach((d) => {
      const data = d.data();
      const createdAt = data.createdAt || 0;
      if (createdAt < sevenDaysAgo) {
        // Auto-delete logs older than 7 days
        deletePromises.push(deleteDoc(doc(db, "admin_audit_logs", d.id)));
      } else {
        items.push({ id: d.id, ...data });
      }
    });

    // Silently clean up old logs in background
    if (deletePromises.length > 0) {
      Promise.all(deletePromises).catch(() => {});
    }

    items.sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
    auditLogs = items;
    renderAuditLogsTable(auditLogs);
  } catch (err) {
    auditTableBody.innerHTML = `<tr><td colspan="5" class="loading-state">Audit logs will appear as admin actions are performed.</td></tr>`;
  }
}

function renderAuditLogsTable(list) {
  if (list.length === 0) {
    auditTableBody.innerHTML = `<tr><td colspan="5" class="loading-state">No administrative actions logged yet.</td></tr>`;
    return;
  }

  auditTableBody.innerHTML = list.map((log) => `
    <tr>
      <td>${formatDate(log.createdAt)}</td>
      <td><strong>${log.adminIdentity || 'Super Admin'}</strong></td>
      <td><span class="badge badge-primary">${log.action}</span></td>
      <td><code>${log.target || '--'}</code></td>
      <td><span class="badge badge-success">SUCCESS</span></td>
    </tr>
  `).join("");
}

refreshAuditBtn.addEventListener("click", () => {
  loadAuditLogs();
  showToast("Audit logs updated.");
});

// Toast helper
function showToast(msg) {
  toast.textContent = msg;
  toast.classList.remove("hidden");
  clearTimeout(window.__toastTimeout);
  window.__toastTimeout = setTimeout(() => toast.classList.add("hidden"), 3500);
}

// --- 9. License Keys Management (No-Gmail Premium) ---
async function loadLicenseKeys() {
  try {
    const q = query(collection(db, "license_keys"));
    const snap = await getDocs(q);
    const items = [];
    snap.forEach((d) => items.push({ id: d.id, ...d.data() }));
    items.sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
    licenseKeys = items;

    renderLicenseKeysTable(licenseKeys);
    updateLicenseStats(licenseKeys);
  } catch (err) {
    console.error("Error fetching license keys:", err);
    if (licenseKeysTableBody) {
      licenseKeysTableBody.innerHTML = `<tr><td colspan="7" class="loading-state">Error loading license keys. Check permissions.</td></tr>`;
    }
  }
}

function updateLicenseStats(keys) {
  if (licTotalKeys) licTotalKeys.textContent = keys.length;
  if (sidebarLicensesBadge) sidebarLicensesBadge.textContent = keys.length;
  const activeCount = keys.filter(k => k.isRedeemed && k.status !== "REVOKED").length;
  const availableCount = keys.filter(k => !k.isRedeemed && k.status !== "REVOKED").length;
  if (licActiveKeys) licActiveKeys.textContent = activeCount;
  if (licAvailableKeys) licAvailableKeys.textContent = availableCount;
}

function renderLicenseKeysTable(keys) {
  if (!licenseKeysTableBody) return;
  if (keys.length === 0) {
    licenseKeysTableBody.innerHTML = `<tr><td colspan="7" class="loading-state">No license keys generated yet. Use the form above to generate an activation code.</td></tr>`;
    return;
  }

  licenseKeysTableBody.innerHTML = keys.map((key) => {
    const isRevoked = key.status === "REVOKED";
    const statusBadge = isRevoked 
      ? `<span class="badge badge-danger">REVOKED</span>` 
      : key.isRedeemed 
        ? `<span class="badge badge-success">REDEEMED</span>` 
        : `<span class="badge badge-warning">AVAILABLE</span>`;

    const planBadgeClass = key.planType === "LIFETIME" ? "badge-primary" : key.planType === "YEARLY" ? "badge-success" : "badge-outline";
    const expiryText = key.planType === "LIFETIME" ? "Never (Lifetime)" : (key.expiresAt ? formatDate(key.expiresAt) : `${key.validityDays || 30} Days`);

    return `
      <tr>
        <td><strong style="font-family: var(--font-mono); color: #60a5fa;">${key.code || key.id}</strong></td>
        <td><span class="badge ${planBadgeClass}">${key.planType || 'LIFETIME'}</span></td>
        <td>${statusBadge}</td>
        <td>${key.customerNote || '<span style="color: #64748b;">--</span>'}</td>
        <td><code>${key.redeemedByDeviceId || key.redeemedByUid || 'Not Claimed'}</code></td>
        <td>${expiryText}</td>
        <td style="text-align: right;">
          <div style="display: flex; gap: 6px; justify-content: flex-end;">
            <button class="btn btn-secondary btn-sm" onclick="window.copyLicenseKey('${key.code || key.id}')">Copy</button>
            ${!isRevoked ? `<button class="btn btn-warning btn-sm" onclick="window.revokeLicenseKeyPrompt('${key.code || key.id}')">Revoke</button>` : ''}
            <button class="btn btn-danger btn-sm" onclick="window.deleteLicenseKeyPrompt('${key.code || key.id}')">Delete</button>
          </div>
        </td>
      </tr>
    `;
  }).join("");
}

// Global window actions for License Keys
window.copyLicenseKey = (code) => {
  navigator.clipboard.writeText(code).then(() => {
    showToast(`Key copied: ${code}`);
  });
};

window.revokeLicenseKeyPrompt = async (code) => {
  if (!confirm(`Are you sure you want to revoke license key ${code}? The user will immediately lose Premium access.`)) return;
  try {
    await setDoc(doc(db, "license_keys", code), { status: "REVOKED", revokedAt: Date.now() }, { merge: true });
    await recordAuditLog("REVOKE_LICENSE_KEY", code);
    showToast(`Revoked key ${code}`);
    loadLicenseKeys();
  } catch (e) {
    alert("Error revoking key: " + e.message);
  }
};

window.deleteLicenseKeyPrompt = async (code) => {
  if (!confirm(`Permanently delete license key record ${code}?`)) return;
  try {
    await deleteDoc(doc(db, "license_keys", code));
    await recordAuditLog("DELETE_LICENSE_KEY", code);
    showToast(`Deleted key ${code}`);
    loadLicenseKeys();
  } catch (e) {
    alert("Error deleting key: " + e.message);
  }
};

// Generate Key Form Handler
if (generateKeyForm) {
  generateKeyForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    const plan = keyPlanSelect.value;
    const note = keyCustomerInput.value.trim();

    // Random hex generator e.g. VAULT-LIFE-A48B-9321
    const rand1 = Math.random().toString(36).substring(2, 6).toUpperCase();
    const rand2 = Math.random().toString(36).substring(2, 6).toUpperCase();
    const prefix = plan === "LIFETIME" ? "LIFE" : plan === "YEARLY" ? "1YR" : "30D";
    const generatedCode = `VAULT-${prefix}-${rand1}-${rand2}`;

    const validityDays = plan === "LIFETIME" ? 99999 : plan === "YEARLY" ? 365 : 30;

    try {
      await setDoc(doc(db, "license_keys", generatedCode), {
        code: generatedCode,
        planType: plan,
        validityDays: validityDays,
        customerNote: note,
        isRedeemed: false,
        status: "ACTIVE",
        createdAt: Date.now()
      });

      await recordAuditLog("GENERATE_LICENSE_KEY", generatedCode, { plan, note });

      // Show alert banner
      if (newKeyCodeText) newKeyCodeText.textContent = generatedCode;
      if (newKeyPlanText) newKeyPlanText.textContent = `${plan} Premium Activation Key`;
      if (newKeyAlert) newKeyAlert.classList.remove("hidden");

      if (btnCopyKey) {
        btnCopyKey.onclick = () => {
          navigator.clipboard.writeText(generatedCode).then(() => showToast("Copied: " + generatedCode));
        };
      }

      if (btnCopyWhatsAppMsg) {
        btnCopyWhatsAppMsg.onclick = () => {
          const msg = `🌟 *Calculator Vault Premium Activation*\n\nHello! Here is your official Premium Activation Key:\n👉 *${generatedCode}*\n\nPlan: *${plan} Premium*\n\n*How to Activate:*\n1. Open Calculator Vault app on your mobile.\n2. Tap Settings -> Premium.\n3. Scroll to *Redeem Activation Code*.\n4. Paste this code and tap *Activate Key*.\n\nEnjoy unlimited cloud vault privileges! (Save this code; works even if you reinstall).`;
          navigator.clipboard.writeText(msg).then(() => showToast("WhatsApp text copied!"));
        };
      }

      keyCustomerInput.value = "";
      showToast("License key created successfully!");
      loadLicenseKeys();
    } catch (err) {
      alert("Error generating license key: " + err.message);
    }
  });
}

if (refreshKeysBtn) {
  refreshKeysBtn.addEventListener("click", () => {
    loadLicenseKeys();
    showToast("License keys refreshed.");
  });
}

// --- 10. Dynamic Limits Remote Config ---
async function loadDynamicLimits() {
  try {
    const docSnap = await getDoc(doc(db, "system_config", "app_limits"));
    if (docSnap.exists()) {
      const data = docSnap.data();
      if (document.getElementById("limitFreeRecordings") && data.freeMaxRecordings !== undefined) {
        document.getElementById("limitFreeRecordings").value = data.freeMaxRecordings;
      }
      if (document.getElementById("limitFreeLengthMins") && data.freeMaxVideoLengthMins !== undefined) {
        document.getElementById("limitFreeLengthMins").value = data.freeMaxVideoLengthMins;
      }
      if (document.getElementById("limitPremiumRecordings") && data.premiumMaxRecordings !== undefined) {
        document.getElementById("limitPremiumRecordings").value = data.premiumMaxRecordings;
      }
      if (document.getElementById("limitPremiumLengthMins") && data.premiumMaxVideoLengthMins !== undefined) {
        document.getElementById("limitPremiumLengthMins").value = data.premiumMaxVideoLengthMins;
      }
    }
  } catch (err) {
    console.warn("Could not load dynamic limits:", err);
  }
}

if (document.getElementById("dynamicLimitsForm")) {
  document.getElementById("dynamicLimitsForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const freeRec = parseInt(document.getElementById("limitFreeRecordings").value, 10) || 5;
    const freeLength = parseInt(document.getElementById("limitFreeLengthMins").value, 10) || 10;
    const premRec = parseInt(document.getElementById("limitPremiumRecordings").value, 10) || 1000;
    const premLength = parseInt(document.getElementById("limitPremiumLengthMins").value, 10) || 60;

    const payload = {
      freeMaxRecordings: freeRec,
      freeMaxVideoLengthMins: freeLength,
      premiumMaxRecordings: premRec,
      premiumMaxVideoLengthMins: premLength,
      updatedAt: Date.now()
    };

    try {
      await setDoc(doc(db, "system_config", "app_limits"), payload, { merge: true });
      await recordAuditLog("UPDATE_DYNAMIC_LIMITS", "system_config/app_limits", payload);
      showToast(`Saved! Limits synced successfully.`);
    } catch (err) {
      alert("Error saving quota limits: " + err.message);
    }
  });
}

// --- 11. Multi-Account Cloudinary Remote Config (Up to 5 Accounts) ---
async function loadCloudinaryConfig() {
  try {
    const docSnap = await getDoc(doc(db, "system_config", "cloudinary"));
    if (docSnap.exists()) {
      const data = docSnap.data();
      const accounts = Array.isArray(data.accounts) ? data.accounts : [];

      for (let i = 0; i < 5; i++) {
        const cNameEl = document.getElementById(`cld_cloudName_${i}`);
        const presetEl = document.getElementById(`cld_preset_${i}`);
        const labelEl = document.getElementById(`cld_label_${i}`);
        const enabledEl = document.getElementById(`cld_enabled_${i}`);

        if (accounts[i]) {
          if (cNameEl) cNameEl.value = accounts[i].cloudName || "";
          if (presetEl) presetEl.value = accounts[i].uploadPreset || "";
          if (labelEl) labelEl.value = accounts[i].label || "";
          if (enabledEl) enabledEl.checked = accounts[i].enabled !== false;
        } else if (i === 0 && data.cloudName) {
          // Backward compatibility for legacy single-account document
          if (cNameEl) cNameEl.value = data.cloudName || "";
          if (presetEl) presetEl.value = data.uploadPreset || "";
          if (labelEl) labelEl.value = "Primary Account";
          if (enabledEl) enabledEl.checked = true;
        }
      }
    }
  } catch (err) {
    console.warn("Could not load Cloudinary config pool:", err);
  }
}

if (cloudinaryConfigForm) {
  cloudinaryConfigForm.addEventListener("submit", async (e) => {
    e.preventDefault();

    const accounts = [];
    for (let i = 0; i < 5; i++) {
      const cNameEl = document.getElementById(`cld_cloudName_${i}`);
      const presetEl = document.getElementById(`cld_preset_${i}`);
      const labelEl = document.getElementById(`cld_label_${i}`);
      const enabledEl = document.getElementById(`cld_enabled_${i}`);

      const cloudName = cNameEl ? cNameEl.value.trim() : "";
      const uploadPreset = presetEl ? presetEl.value.trim() : "";
      const label = labelEl ? labelEl.value.trim() : `Account ${i + 1}`;
      const enabled = enabledEl ? enabledEl.checked : false;

      if (cloudName && uploadPreset) {
        accounts.push({
          id: `acc_${i + 1}`,
          index: i,
          cloudName,
          uploadPreset,
          label: label || `Account ${i + 1}`,
          enabled: enabled
        });
      }
    }

    if (accounts.length === 0) {
      alert("Please enter at least Account 1 Cloud Name and Upload Preset.");
      return;
    }

    // First active account becomes primary for backward compatibility
    const primary = accounts.find(a => a.enabled) || accounts[0];

    const payload = {
      // Legacy fields for backward compatibility with older app versions
      cloudName: primary.cloudName,
      uploadPreset: primary.uploadPreset,
      isActive: true,

      // Multi-account pool for new resilient app versions
      accounts: accounts,
      rotationMode: "AUTO_FAILOVER", // Android app tries active accounts sequentially
      totalAccounts: accounts.length,
      updatedAt: Date.now()
    };

    try {
      await setDoc(doc(db, "system_config", "cloudinary"), payload, { merge: true });
      await recordAuditLog("UPDATE_CLOUDINARY_POOL", `${accounts.length} Accounts Configured`, {
        accounts: accounts.map(a => `${a.label} (${a.cloudName}) - ${a.enabled ? 'Active' : 'Disabled'}`)
      });
      showToast(`Saved! ${accounts.length} Cloudinary accounts synced with Auto-Failover.`);
    } catch (err) {
      alert("Error saving Cloudinary config pool: " + err.message);
    }
  });
}

// --- 12. Master Security PIN & Access Control ---
async function loadAdminSecurityConfig() {
  try {
    const secDocSnap = await getDoc(doc(db, "system_config", "admin_security"));
    if (secDocSnap.exists()) {
      const data = secDocSnap.data();
      if (pinLoginEnabledCheckbox) {
        pinLoginEnabledCheckbox.checked = data.isPinEnabled !== false;
      }
      if (pinStatusBadge) {
        if (data.isPinEnabled === false) {
          pinStatusBadge.className = "badge badge-danger";
          pinStatusBadge.textContent = "PIN Login Disabled";
        } else if (data.pinHash) {
          pinStatusBadge.className = "badge badge-success";
          pinStatusBadge.textContent = "Custom Master PIN Active";
        } else {
          pinStatusBadge.className = "badge badge-warning";
          pinStatusBadge.textContent = "Default PIN (1234) Active";
        }
      }
    } else {
      if (pinStatusBadge) {
        pinStatusBadge.className = "badge badge-warning";
        pinStatusBadge.textContent = "Default PIN (1234) Active";
      }
    }
  } catch (err) {
    console.warn("Could not load admin security config:", err);
  }
}

if (masterPinConfigForm) {
  masterPinConfigForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    const newPin = (newMasterPinInput ? newMasterPinInput.value : "").trim();
    const confirmPin = (confirmMasterPinInput ? confirmMasterPinInput.value : "").trim();
    const isPinEnabled = pinLoginEnabledCheckbox ? pinLoginEnabledCheckbox.checked : true;

    const updatePayload = {
      isPinEnabled: isPinEnabled,
      updatedAt: Date.now(),
      updatedBy: currentAdminUser ? (currentAdminUser.email || "Master Admin") : "king.khan648k@gmail.com"
    };

    if (newPin || confirmPin) {
      if (newPin.length < 4) {
        alert("Master PIN must be at least 4 characters or digits long for security.");
        return;
      }
      if (newPin !== confirmPin) {
        alert("New PIN and Confirm PIN do not match! Please check and re-enter.");
        return;
      }
      const hashed = await hashPin(newPin);
      updatePayload.pinHash = hashed;
    }

    try {
      await setDoc(doc(db, "system_config", "admin_security"), updatePayload, { merge: true });
      await recordAuditLog("UPDATE_ADMIN_SECURITY_PIN", "Master PIN updated", {
        isPinEnabled,
        pinUpdated: !!updatePayload.pinHash
      });

      if (newMasterPinInput) newMasterPinInput.value = "";
      if (confirmMasterPinInput) confirmMasterPinInput.value = "";

      showToast("🔒 Master Security PIN & Access Settings saved successfully!");
      loadAdminSecurityConfig();
    } catch (err) {
      alert("Error saving Master PIN: " + err.message);
    }
  });
}

// ── Android App Updates & Releases Management ──────────────────────────────────
const sidebarUpdateBadge = document.getElementById("sidebarUpdateBadge");
const updateLiveBadge = document.getElementById("updateLiveBadge");
const displayLiveVersion = document.getElementById("displayLiveVersion");
const displayLiveCode = document.getElementById("displayLiveCode");
const displayLiveMandatory = document.getElementById("displayLiveMandatory");
const displayLiveUrl = document.getElementById("displayLiveUrl");
const btnTestLiveApk = document.getElementById("btnTestLiveApk");
const displayLiveNotes = document.getElementById("displayLiveNotes");
const displayLiveDate = document.getElementById("displayLiveDate");

const appUpdateReleaseForm = document.getElementById("appUpdateReleaseForm");
const releaseVersionName = document.getElementById("releaseVersionName");
const releaseVersionCode = document.getElementById("releaseVersionCode");
const releaseMinVersionCode = document.getElementById("releaseMinVersionCode");
const releaseApkUrl = document.getElementById("releaseApkUrl");
const btnTestInputUrl = document.getElementById("btnTestInputUrl");
const releaseNotes = document.getElementById("releaseNotes");
const releaseMandatoryCheck = document.getElementById("releaseMandatoryCheck");
const btnPublishAppUpdate = document.getElementById("btnPublishAppUpdate");

let cachedLiveAppUpdateConfig = null;

function renderAppUpdateInfo(config) {
  if (!config) return;
  cachedLiveAppUpdateConfig = config;
  const vName = config.versionName || "1.0.0";
  const vCode = config.versionCode || 1;
  const isMandatory = config.isMandatory === true;
  const apk = config.apkUrl || "";
  const notes = config.releaseNotes || "No release notes specified.";
  const publishedMs = config.publishedAtEpochMs || Date.now();

  if (sidebarUpdateBadge) sidebarUpdateBadge.textContent = "v" + vName;
  if (updateLiveBadge) {
    updateLiveBadge.className = "badge badge-success";
    updateLiveBadge.textContent = "Live Broadcast Active";
  }
  if (displayLiveVersion) displayLiveVersion.textContent = "v" + vName;
  if (displayLiveCode) displayLiveCode.textContent = `(Code: ${vCode})`;
  if (displayLiveMandatory) {
    displayLiveMandatory.className = isMandatory ? "badge badge-danger" : "badge badge-primary";
    displayLiveMandatory.textContent = isMandatory ? "FORCED MANDATORY" : "Optional Update";
  }
  if (displayLiveUrl) {
    displayLiveUrl.textContent = apk || "No download URL configured";
    displayLiveUrl.title = apk;
  }
  if (btnTestLiveApk) {
    if (apk && apk.startsWith("http")) {
      btnTestLiveApk.href = apk;
      btnTestLiveApk.style.display = "inline-flex";
    } else {
      btnTestLiveApk.style.display = "none";
    }
  }
  if (displayLiveNotes) displayLiveNotes.textContent = notes;
  if (displayLiveDate) displayLiveDate.textContent = formatDate(publishedMs);

  // Pre-fill publish form with next version suggestions if empty
  if (releaseVersionName && !releaseVersionName.value) {
    releaseVersionName.value = vName;
  }
  if (releaseVersionCode && !releaseVersionCode.value) {
    releaseVersionCode.value = vCode + 1;
  }
  if (releaseMinVersionCode && !releaseMinVersionCode.value) {
    releaseMinVersionCode.value = config.minSupportedVersionCode || 1;
  }
  if (releaseApkUrl && !releaseApkUrl.value) {
    releaseApkUrl.value = apk;
  }
  if (releaseNotes && !releaseNotes.value) {
    releaseNotes.value = notes;
  }
  if (releaseMandatoryCheck) {
    releaseMandatoryCheck.checked = isMandatory;
  }
}

async function loadAppUpdateConfig() {
  try {
    const docRef = doc(db, "system_config", "app_update");
    const snap = await getDoc(docRef);
    if (snap.exists()) {
      renderAppUpdateInfo(snap.data());
    } else {
      renderAppUpdateInfo({
        versionName: "1.0.0",
        versionCode: 1,
        minSupportedVersionCode: 1,
        apkUrl: "",
        releaseNotes: "Initial release with secret vault, automatic cloud backups, and fast media restore.",
        isMandatory: false,
        publishedAtEpochMs: Date.now()
      });
    }
  } catch (err) {
    console.warn("Could not load app_update config from Firestore:", err);
  }
}

if (btnTestInputUrl && releaseApkUrl) {
  btnTestInputUrl.addEventListener("click", () => {
    const url = releaseApkUrl.value.trim();
    if (url && url.startsWith("http")) {
      window.open(url, "_blank");
    } else {
      alert("Please enter a valid HTTP/HTTPS download link first.");
    }
  });
}

if (appUpdateReleaseForm) {
  appUpdateReleaseForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    const vName = (releaseVersionName ? releaseVersionName.value : "").trim();
    let vCode = parseInt(releaseVersionCode ? releaseVersionCode.value : "0", 10);
    if (isNaN(vCode) || vCode <= 0) {
      vCode = ((cachedLiveAppUpdateConfig && cachedLiveAppUpdateConfig.versionCode) ? cachedLiveAppUpdateConfig.versionCode : 7) + 1;
    }
    const minCode = parseInt(releaseMinVersionCode ? releaseMinVersionCode.value : "1", 10) || 1;
    const apk = (releaseApkUrl ? releaseApkUrl.value : "").trim();
    const notes = (releaseNotes ? releaseNotes.value : "").trim();
    const isMandatory = releaseMandatoryCheck ? releaseMandatoryCheck.checked : false;

    if (!vName) {
      alert("Please enter a version name (e.g. 2.7 or 3.0).");
      return;
    }
    if (!apk || !apk.startsWith("http")) {
      alert("Please enter a valid HTTP or HTTPS APK download URL.");
      return;
    }

    if (!confirm(`Are you sure you want to broadcast update v${vName} to all users? App users will be notified to update according to this Version Name.`)) {
      return;
    }

    if (btnPublishAppUpdate) {
      btnPublishAppUpdate.disabled = true;
      btnPublishAppUpdate.textContent = "Publishing Update to Cloud...";
    }

    try {
      const updateData = {
        versionName: vName,
        versionCode: vCode,
        minSupportedVersionCode: minCode,
        apkUrl: apk,
        releaseNotes: notes,
        isMandatory: isMandatory,
        publishedAtEpochMs: Date.now()
      };

      await setDoc(doc(db, "system_config", "app_update"), updateData);

      try {
        await recordAuditLog("PUBLISH_APP_UPDATE", `system_config/app_update (v${vName} / code:${vCode})`, "SUCCESS");
      } catch (_logErr) {
        // Non-blocking audit log
      }

      renderAppUpdateInfo(updateData);
      showToast(`🚀 Update v${vName} (Code: ${vCode}) published to all users!`);
    } catch (err) {
      console.error("Failed to publish app update:", err);
      alert("Failed to publish app update: " + err.message);
    } finally {
      if (btnPublishAppUpdate) {
        btnPublishAppUpdate.disabled = false;
        btnPublishAppUpdate.innerHTML = `<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line></svg> Publish Update to All App Users`;
      }
    }
  });
}



